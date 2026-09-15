<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->

# AlphaFold 3 over MCP — a new user, a public runtime image, one prompt

What this demonstrates: **someone who owns nothing can do specialised science in
Texera without building anything.** They pick a runtime image another user
published, start a computing unit from it, and a workflow runs on libraries the
deployment's default image has never heard of.

## What is real, and what is not

Say this plainly if it comes up, because the distinction is the interesting part.

| Step | What actually runs |
| --- | --- |
| Evidence search | **AlphaFold 3, genuinely.** `alphafold3.data.tools.jackhmmer` — the class AF3's own data pipeline calls — shelling out to the real `jackhmmer` binary, on Python 3.12 |
| 3D structure | **ESMFold** via the public ESM Atlas API, with the experimentally determined RCSB structure as a fallback. Each row records which one it used |

**AlphaFold 3 does not fold anything here, and cannot.** Its model weights are
released by DeepMind only under a separate licence and are not in the image;
there is also no GPU. What AF3 contributes is its data pipeline, which is the
half that decides whether a fold prediction is worth trusting at all — alignment
depth is the standard predictor of AlphaFold confidence. The workflow is
therefore a real question a biologist asks: *which of these will AlphaFold do
well on, and what do they look like?*

## Setup (about two minutes)

**1. The cluster must be up**, with the `alphafold3` runtime image published:

```bash
minikube start -p texera-mount
```

**2. Start the execution proxy** and leave it running:

```bash
python3 bin/demo/alphafold-mcp/cu-execution-proxy.py
```

On Kubernetes each computing unit is its own pod and the gateway does not route
to them, so an MCP client on a laptop has nowhere to send a run request. The
proxy reads the unit id out of the request path and port-forwards to that pod on
demand — which is what lets the assistant *run* the workflow rather than only
build it.

**3. Point Claude Desktop at the deployment.** In its MCP config:

```json
{
  "mcpServers": {
    "texera": {
      "command": "node",
      "args": ["/home/ali/IdeaProjects/texera-worktrees/forks/rodeo-pipeline/mcp-service/dist/index.js"],
      "env": {
        "TEXERA_BASE_URL": "http://192.168.58.2:31675",
        "TEXERA_EXECUTION_URL": "http://127.0.0.1:8085",
        "TEXERA_TOKEN": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJiaW9sb2dpc3QiLCJ1c2VySWQiOjMsImdvb2dsZUlkIjpudWxsLCJlbWFpbCI6ImJpb2xvZ2lzdEBkZW1vLm9yZyIsInJvbGUiOiJSRUdVTEFSIiwiZ29vZ2xlQXZhdGFyIjpudWxsLCJleHAiOjE3ODg0NzY0ODl9.Uvm5KtnZoojMPOBD4E4r0uI-IqX0PcJbiRj-0wfiV9w",
        "TEXERA_RUN_TIMEOUT_SECONDS": "420"
      }
    }
  }
}
```

That token is the **`biologist`** account (`biologist@demo.org` / `demo12345`),
which owns nothing but the demo dataset — deliberately, so the public runtime
image is doing real work. It is valid for seven days.

## The prompt

Paste this in as-is:

> Hi — I'm a biologist and this is my first time using Texera.
>
> In my dataset **protein-structures** I've put four small human proteins
> (`proteins.csv`) and a file of related sequences to compare them against
> (`ubiquitin_family.fasta`). The four all work by attaching to other proteins to
> change what happens to them, and honestly they look pretty similar to me.
>
> What I want to know is how much evolutionary evidence there is behind each one
> — I've been told AlphaFold does a better job when a protein has lots of known
> relatives, so I'd like to see them compared side by side. Then for the ones
> that have good support, I'd like to actually see the 3D shape and be able to
> spin it around.
>
> Could you set the whole thing up and run it, so I can just look at the answer?

## What you should get

A workflow of **six operators, four of them native**:

```
Protein list (CSV)  ─▶  AlphaFold 3 evidence search  ─┬─▶  Evidence per protein (bar chart)
   CSVFileScan              Python UDF                │
                                                      └─▶  Keep well-supported  ─▶  Fold into 3D  ─▶  3D viewer
                                                              Filter                 Python UDF       HTMLVisualizer
```

and results along these lines:

| protein | alignment depth | kept? | structure |
| --- | --- | --- | --- |
| Ubiquitin | 466 | yes | ESMFold prediction, pLDDT 90 |
| NEDD8 | 459 | yes | 3D structure |
| SUMO1 | 322 | yes | 3D structure |
| SUMO2 | 51 | **filtered out** | — |

SUMO2 dropping out is the point of the filter, not a bug: it has far fewer
relatives in this small reference database, so it is exactly the protein whose
prediction you would trust least.

The 3D viewer is 3Dmol.js with the structure embedded — it spins, and you can
drag to rotate.

## If it goes wrong on the day

- **"No runtime image available"** — the image is published, but check the
  account: `runtime_image_list` shows public images to everyone, so an empty
  list means the token is wrong or the cluster is down.
- **A UDF fails with `No module named 'alphafold3'`** — the operator is running
  on the engine's own Python. It needs `defaultEnv=false` and
  `envName="alphafold3"`. Both MCP tools say this, but if the assistant misses
  it, that is the fix.
- **The unit will not schedule** — the node runs out of CPU with several units
  alive. `computing_unit_list`, then terminate the ones that are not in use.
- **The fold step is slow** — the ESM Atlas API is free and rate-limits. The
  fallback to RCSB covers it; a run should still finish inside a few minutes.

## Files

```
cu-execution-proxy.py   makes the per-pod run endpoint reachable from a laptop
README.md               this file
```

The reference database is built by `../alphafold3/fetch-sequence-db.sh`. UniProt
sequences are CC BY 4.0 and are fetched rather than committed.
