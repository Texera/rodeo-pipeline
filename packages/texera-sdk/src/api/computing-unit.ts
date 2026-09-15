/**
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import type { TexeraClient } from "../client";

/**
 * A computing unit is where a workflow actually runs. On Kubernetes each unit
 * is its own pod, which is why the sync-execution endpoint is templated per
 * `cuid` (see `TexeraClient.executionEndpointFor`). Deployments may also offer
 * `local` units.
 */
export interface WorkflowComputingUnit {
  cuid: number;
  uid: number;
  name: string;
  creationTime?: number;
  terminateTime?: number | null;
  type?: string;
  uri?: string;
  /**
   * JSON blob the manager records alongside the unit: resource limits, and the
   * runtime image it was started from (riid / runtimeImageName / runtimeImageTag).
   * Returned by the API but not otherwise modelled, so callers parse what they need.
   */
  resource?: string;
}

export interface WorkflowComputingUnitMetrics {
  cpuUsage: string;
  memoryUsage: string;
}

export interface DashboardWorkflowComputingUnit {
  computingUnit: WorkflowComputingUnit;
  /** `Running` | `Pending` | `Terminated` | … */
  status: string;
  metrics: WorkflowComputingUnitMetrics;
  isOwner: boolean;
  accessPrivilege: string;
  ownerGoogleAvatar?: string;
  ownerName?: string;
}

export interface ComputingUnitLimitOptions {
  cpuLimitOptions: string[];
  memoryLimitOptions: string[];
  gpuLimitOptions: string[];
}

export interface ComputingUnitTypes {
  typeOptions: string[];
}

export interface ComputingUnitCreationParams {
  name: string;
  unitType: string;
  cpuLimit: string;
  memoryLimit: string;
  gpuLimit: string;
  jvmMemorySize: string;
  shmSize: string;
  uri?: string;
  /** Runtime image to start from. Absent means the deployment's default image. */
  riid?: number;
}

export async function listComputingUnits(client: TexeraClient): Promise<DashboardWorkflowComputingUnit[]> {
  return client.request<DashboardWorkflowComputingUnit[]>("computingUnit", "/api/computing-unit");
}

export async function getComputingUnit(client: TexeraClient, cuid: number): Promise<DashboardWorkflowComputingUnit> {
  return client.request<DashboardWorkflowComputingUnit>("computingUnit", `/api/computing-unit/${cuid}`);
}

/**
 * The resource values a deployment accepts. The backend rejects anything not in
 * these lists with 403, so a caller should pick from here rather than guess.
 */
export async function getComputingUnitLimitOptions(client: TexeraClient): Promise<ComputingUnitLimitOptions> {
  return client.request<ComputingUnitLimitOptions>("computingUnit", "/api/computing-unit/limits");
}

export async function getComputingUnitTypes(client: TexeraClient): Promise<ComputingUnitTypes> {
  return client.request<ComputingUnitTypes>("computingUnit", "/api/computing-unit/types");
}

export async function createComputingUnit(
  client: TexeraClient,
  params: ComputingUnitCreationParams
): Promise<DashboardWorkflowComputingUnit> {
  return client.request<DashboardWorkflowComputingUnit>("computingUnit", "/api/computing-unit/create", {
    method: "POST",
    json: params,
    // Pod scheduling and image pull can take a while on a cold node.
    timeoutMs: 180_000,
  });
}

export async function terminateComputingUnit(client: TexeraClient, cuid: number): Promise<void> {
  // The backend maps this route as @DELETE (ComputingUnitManagingResource); POST returns 405.
  await client.request<void>("computingUnit", `/api/computing-unit/${cuid}/terminate`, { method: "DELETE" });
}

export async function renameComputingUnit(client: TexeraClient, cuid: number, name: string): Promise<void> {
  await client.request<void>("computingUnit", `/api/computing-unit/${cuid}/rename/${encodeURIComponent(name)}`, {
    method: "POST",
  });
}

/** True when the unit can accept an execution right now. */
export function isComputingUnitReady(unit: DashboardWorkflowComputingUnit): boolean {
  return unit.status === "Running" && !unit.computingUnit.terminateTime;
}

// ---------------------------------------------------------------------------
// Model mounts
// ---------------------------------------------------------------------------

/**
 * A model version made visible inside a computing unit's pod as a read-only
 * FUSE filesystem, rather than copied into it. Nothing is transferred at mount
 * time — bytes are fetched on read — so mounting a multi-gigabyte checkpoint is
 * effectively free and only the parts a UDF touches ever move.
 *
 * Mounts live in the kernel's mount table on the node, not in a database: they
 * are per-computing-unit and disappear with the pod.
 */
export interface MountedModelInfo {
  /** `/models/ownerEmail/modelName/versionName`, empty if it could not be resolved. */
  modelPath: string;
  /** LakeFS repository, i.e. `model-<mid>`. */
  repositoryName: string;
  /** The version's commit hash — a mount is pinned to one commit. */
  commitHash: string;
  /** Absolute path the model appears at inside the pod. */
  mountPath: string;
}

export async function listMountedModels(client: TexeraClient, cuid: number): Promise<MountedModelInfo[]> {
  return client.request<MountedModelInfo[]>("computingUnit", `/api/computing-unit/${cuid}/mounts`);
}

/**
 * Mounts a model version onto a computing unit. `modelPath` is the logical
 * `/models/ownerEmail/modelName/versionName` string, the same value a Python
 * UDF's `models` UI parameter holds.
 *
 * Kubernetes units only — a local unit has no node mounter to talk to.
 */
export async function mountModel(client: TexeraClient, cuid: number, modelPath: string): Promise<MountedModelInfo> {
  return client.request<MountedModelInfo>("computingUnit", `/api/computing-unit/${cuid}/mounts`, {
    method: "POST",
    json: { modelPath },
    timeoutMs: 120_000,
  });
}

export async function unmountModel(client: TexeraClient, cuid: number, modelPath: string): Promise<void> {
  await client.request<void>("computingUnit", `/api/computing-unit/${cuid}/mounts`, {
    method: "DELETE",
    json: { modelPath },
  });
}
