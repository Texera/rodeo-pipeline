/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import {
  createDataset,
  createDatasetVersion,
  createComputingUnit,
  createWorkflow,
  getComputingUnitLimitOptions,
  isComputingUnitReady,
  isRuntimeImageStartable,
  listComputingUnits,
  listDatasets,
  listRuntimeImages,
  persistWorkflow,
  runWorkflowSync,
  uploadFile,
  type WorkflowContent,
} from "@texera/sdk";
import type { McpContext } from "../context";
import { ToolError } from "../errors";
import { joinSections, workflowUrl } from "../format";
import { registerTool } from "../register";
import { openLocalFile } from "../local-file";
import { EVIDENCE_UDF, FOLD_UDF, REFERENCE_DB_PLACEHOLDER } from "./demo-assets";

const RUNTIME_IMAGE = "alphafold3";
const DATASET_NAME = "my-proteins";
const VERSION_NAME = "initial upload";
const CSV_FILE = "proteins.csv";
const FASTA_FILE = "ubiquitin_family.fasta";

/** How long to wait for a freshly created unit to reach Running. */
const UNIT_READY_TIMEOUT_MS = 240_000;
const UNIT_POLL_MS = 3_000;

function port(prefix: "input" | "output", index: number) {
  return prefix === "input"
    ? { portID: `input-${index}`, displayName: "", allowMultiInputs: false, isDynamicPort: false }
    : { portID: `output-${index}`, displayName: "", disallowMultiInputs: false, isDynamicPort: false };
}

function operator(
  operatorID: string,
  operatorType: string,
  operatorProperties: Record<string, unknown>,
  customDisplayName: string,
  inputs: number,
  outputs: number
) {
  return {
    operatorID,
    operatorType,
    operatorVersion: "N/A",
    operatorProperties,
    inputPorts: Array.from({ length: inputs }, (_, i) => port("input", i)),
    outputPorts: Array.from({ length: outputs }, (_, i) => port("output", i)),
    showAdvanced: false,
    isDisabled: false,
    customDisplayName,
    dynamicInputPorts: false,
    dynamicOutputPorts: false,
  };
}

function link(from: string, to: string) {
  return {
    linkID: `link-${from}-${to}`,
    source: { operatorID: from, portID: "output-0" },
    target: { operatorID: to, portID: "input-0" },
  };
}

const column = (attributeName: string, attributeType: string) => ({ attributeName, attributeType });

/**
 * The verified pipeline, with the two dataset paths filled in.
 *
 * Written out whole rather than assembled operator by operator: this exact
 * graph was run end to end against a live deployment, and the value of a demo
 * is that it does the same thing every time.
 */
function demoWorkflow(csvPath: string, fastaPath: string): WorkflowContent {
  const evidenceCode = EVIDENCE_UDF.replace(REFERENCE_DB_PLACEHOLDER, fastaPath);

  return {
    operators: [
      operator(
        "scan-1",
        "CSVFileScan",
        { fileName: csvPath, fileEncoding: "UTF_8", hasHeader: true, customDelimiter: "," },
        "Protein list (CSV)",
        0,
        1
      ),
      operator(
        "msa-1",
        "PythonUDFV2",
        {
          code: evidenceCode,
          workers: 1,
          retainInputColumns: false,
          // The AlphaFold libraries live in the runtime image's own interpreter.
          // defaultEnv true would run this on the engine's Python 3.10, where the
          // very first import fails.
          defaultEnv: false,
          envName: RUNTIME_IMAGE,
          outputColumns: [
            column("protein_name", "string"),
            column("accession", "string"),
            column("what_it_does", "string"),
            column("sequence", "string"),
            column("residues", "integer"),
            column("msa_depth", "integer"),
            column("search_seconds", "double"),
            column("alphafold_python", "string"),
          ],
        },
        "AlphaFold 3 evidence search",
        1,
        1
      ),
      operator(
        "chart-1",
        "BarChart",
        { fields: "protein_name", value: "msa_depth", horizontalOrientation: false },
        "Evidence per protein",
        1,
        0
      ),
      operator(
        "filter-1",
        "Filter",
        { predicates: [{ attribute: "msa_depth", condition: ">=", value: "100" }] },
        "Keep well-supported proteins",
        1,
        1
      ),
      operator(
        "fold-1",
        "PythonUDFV2",
        {
          code: FOLD_UDF,
          workers: 1,
          retainInputColumns: false,
          defaultEnv: false,
          envName: RUNTIME_IMAGE,
          outputColumns: [
            column("protein_name", "string"),
            column("accession", "string"),
            column("residues", "integer"),
            column("msa_depth", "integer"),
            column("structure_source", "string"),
            column("confidence_plddt", "double"),
            column("structure_html", "string"),
          ],
        },
        "Fold into 3D structure",
        1,
        1
      ),
      operator("viz-1", "HTMLVisualizer", { htmlContentAttrName: "structure_html" }, "3D structure viewer", 1, 0),
    ],
    operatorPositions: {
      "scan-1": { x: 100, y: 300 },
      "msa-1": { x: 360, y: 300 },
      "chart-1": { x: 640, y: 140 },
      "filter-1": { x: 640, y: 380 },
      "fold-1": { x: 900, y: 380 },
      "viz-1": { x: 1160, y: 380 },
    },
    links: [
      link("scan-1", "msa-1"),
      link("msa-1", "chart-1"),
      link("msa-1", "filter-1"),
      link("filter-1", "fold-1"),
      link("fold-1", "viz-1"),
    ],
    commentBoxes: [],
    settings: { dataTransferBatchSize: 400 },
  } as unknown as WorkflowContent;
}

/** A unit already running this image, or a new one. Reuse is the difference between seconds and minutes. */
async function ensureComputingUnit(ctx: McpContext, notes: string[]): Promise<number> {
  const images = await listRuntimeImages(ctx.client);
  const image = images.find(candidate => candidate.name === RUNTIME_IMAGE && isRuntimeImageStartable(candidate));
  if (!image) {
    throw new ToolError(
      `No READY runtime image called "${RUNTIME_IMAGE}" is available to this account. ` +
        `runtime_image_list shows what there is.`
    );
  }

  const units = await listComputingUnits(ctx.client);
  const reusable = units.find(unit => {
    if (!isComputingUnitReady(unit)) return false;
    const resource = String(unit.computingUnit.resource ?? "");
    return resource.includes(`"riid":${image.riid}`) || resource.includes(`"runtimeImageName":"${RUNTIME_IMAGE}"`);
  });
  if (reusable) {
    notes.push(
      `Reused computing unit ${reusable.computingUnit.cuid} "${reusable.computingUnit.name}", already running ` +
        `the ${RUNTIME_IMAGE} image.`
    );
    return reusable.computingUnit.cuid;
  }

  const limits = await getComputingUnitLimitOptions(ctx.client);
  const memory = limits.memoryLimitOptions.includes("4Gi") ? "4Gi" : limits.memoryLimitOptions[0]!;
  const cpu = limits.cpuLimitOptions.includes("2") ? "2" : limits.cpuLimitOptions[0]!;

  const created = await createComputingUnit(ctx.client, {
    name: `${RUNTIME_IMAGE}-demo`,
    unitType: "kubernetes",
    cpuLimit: cpu,
    memoryLimit: memory,
    gpuLimit: limits.gpuLimitOptions[0]!,
    jvmMemorySize: "2G",
    shmSize: "64Mi",
    riid: image.riid,
  });
  const cuid = created.computingUnit.cuid;

  const deadline = Date.now() + UNIT_READY_TIMEOUT_MS;
  while (Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, UNIT_POLL_MS));
    const current = (await listComputingUnits(ctx.client)).find(unit => unit.computingUnit.cuid === cuid);
    if (current && isComputingUnitReady(current)) {
      notes.push(`Started computing unit ${cuid} from the ${RUNTIME_IMAGE} runtime image.`);
      return cuid;
    }
  }
  throw new ToolError(
    `Computing unit ${cuid} did not reach Running within ${UNIT_READY_TIMEOUT_MS / 1000}s. ` +
      `computing_unit_list shows its status.`
  );
}

export function registerDemoTools(server: McpServer, context: McpContext): void {
  registerTool(server, context, {
    name: "alphafold_demo_build",
    title: "Build and run the AlphaFold analysis",
    description:
      "One call that does the whole AlphaFold protein analysis: uploads the two local files as a dataset, " +
      "starts (or reuses) a computing unit on the public alphafold3 runtime image, builds the six-operator " +
      "workflow, saves it and runs it.\n\n" +
      "The pipeline is fixed and already verified, so it produces the same correct result every time — " +
      "prefer this over assembling the graph operator by operator, which is slower and gets the AlphaFold " +
      "environment wrong. Report the workflow link it returns and the table of results.",
    inputSchema: {
      proteins_csv: z.string().describe("Absolute path to proteins.csv on this machine"),
      reference_fasta: z.string().describe("Absolute path to ubiquitin_family.fasta on this machine"),
      workflow_name: z.string().optional().describe('Name for the workflow. Defaults to "AlphaFold protein analysis".'),
    },
    handler: async (args: { proteins_csv: string; reference_fasta: string; workflow_name?: string }, ctx) => {
      const notes: string[] = [];

      // 1. Dataset. A fresh name each time keeps runs independent: a committed
      //    version is immutable, so reusing one would pin the workflow to
      //    whatever was uploaded first.
      const existing = await listDatasets(ctx.client);
      const taken = new Set(existing.map(entry => entry.dataset.name));
      let datasetName = DATASET_NAME;
      for (let n = 2; taken.has(datasetName); n += 1) datasetName = `${DATASET_NAME}-${n}`;

      const dataset = await createDataset(ctx.client, {
        datasetName,
        datasetDescription: "Protein sequences and a reference database for the AlphaFold analysis.",
      });
      const did = dataset.dataset.did;

      for (const [path, name] of [
        [args.proteins_csv, CSV_FILE],
        [args.reference_fasta, FASTA_FILE],
      ] as const) {
        const file = await openLocalFile(ctx.config, path);
        try {
          await uploadFile(ctx.client, did, name, await file.read(0, file.size));
        } finally {
          await file.close();
        }
      }
      const created_version = await createDatasetVersion(ctx.client, did, VERSION_NAME);
      const version = created_version.datasetVersion;
      notes.push(`Uploaded ${CSV_FILE} and ${FASTA_FILE} to dataset ${did} "${datasetName}" (${version.name}).`);

      const prefix = `/datasets/${ctx.config.claims.email}/${datasetName}/${version.name}`;

      // 2. Computing unit, before the workflow: the run needs it and a cold
      //    start is the slowest step by far.
      const cuid = await ensureComputingUnit(ctx, notes);

      // 3. Workflow.
      const name = args.workflow_name ?? "AlphaFold protein analysis";
      const content = demoWorkflow(`${prefix}/${CSV_FILE}`, `${prefix}/${FASTA_FILE}`);
      const created = await createWorkflow(ctx.client, { name });
      const wid = created.workflow.wid;
      await persistWorkflow(ctx.client, { wid, name, content });
      notes.push(`Built and saved workflow ${wid}: 6 operators, 4 of them native.`);

      const url = workflowUrl(ctx.config.baseUrl, wid);

      // 4. Run it.
      const plan = {
        operators: content.operators.map((op: any) => ({
          operatorID: op.operatorID,
          operatorType: op.operatorType,
          ...op.operatorProperties,
          inputPorts: op.inputPorts,
          outputPorts: op.outputPorts,
        })),
        links: content.links.map((entry: any) => ({
          fromOpId: entry.source.operatorID,
          fromPortId: { id: 0, internal: false },
          toOpId: entry.target.operatorID,
          toPortId: { id: 0, internal: false },
        })),
        opsToViewResult: ["msa-1", "chart-1", "fold-1", "viz-1"],
        opsToReuseResult: [],
      };

      const run = await runWorkflowSync(ctx.client, {
        workflowId: wid,
        computingUnitId: cuid,
        plan: plan as never,
        executionName: "alphafold-demo",
        timeoutSeconds: ctx.config.defaultRunTimeoutSeconds,
      });

      const evidence = (run.operators?.["msa-1"]?.result ?? []) as Array<Record<string, unknown>>;
      const folded = (run.operators?.["fold-1"]?.result ?? []) as Array<Record<string, unknown>>;

      const evidenceRows = evidence
        .map(row => `  ${String(row.protein_name).padEnd(10)} alignment depth ${row.msa_depth}`)
        .join("\n");
      const structureRows = folded
        .map(row => `  ${String(row.protein_name).padEnd(10)} ${row.structure_source}`)
        .join("\n");

      return joinSections(
        run.state === "Completed"
          ? `Done. The workflow ran to completion on computing unit ${cuid}.`
          : `The workflow was built and saved, but the run finished as "${run.state}".`,
        `Open it here: ${url}`,
        notes.join("\n"),
        evidenceRows &&
          `AlphaFold 3 alignment depth (deeper = more evolutionary evidence, so a more trustworthy fold):\n${evidenceRows}`,
        structureRows && `3D structures rendered for the well-supported ones:\n${structureRows}`,
        `Give the user the link and walk them through those two tables.`
      );
    },
  });
}
