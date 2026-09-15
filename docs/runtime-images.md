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

# Runtime images

A **runtime image** is the image a computing unit runs, built from a Dockerfile its owner
wrote. A unit started from one runs that image instead of the deployment's default, so a
workflow can use libraries, system packages and interpreters the default image does not
have.

## Why this replaces Python virtual environments

A Python virtual environment (`virtual_environments`) can only add pip packages to an
interpreter that already exists in the computing-unit image. Three common needs fall
outside that, and no amount of work within the mechanism reaches them:

| Need | Why a venv cannot | A runtime image |
| --- | --- | --- |
| A different Python version | `venv` is built from the image's own interpreter | `FROM` a different base, or install one |
| A system package (`hmmer`, `ffmpeg`, a driver) | a venv holds Python packages only | `apt-get install` as root at build time |
| A package that compiles from source | there is no build step, only `pip install <spec>` | any `RUN` you like |

A shell on a running pod does not close the gap either: that shell is the unprivileged
`texera` user, so `apt-get install` fails, and anything it did install would live in the
container's writable layer and vanish on the next restart. An image is built once, as
root, and is identical for every unit started from it.

Python virtual environments still exist and still work for per-UDF selection. Nothing is
migrated or removed.

## The flow

1. **Runtime Images** in the sidebar → **New runtime image**.
2. Give it a name. The editor is pre-filled with the computing-unit image's own
   Dockerfile, so the starting point is what already exists rather than a blank file.
3. Saving starts a build. The card shows `BUILDING`; **Logs** shows the build's output and
   is readable at any point, during the build or long after it.
4. **Edit** and save to rebuild. **Rebuild** repeats a build without editing.
5. When creating a computing unit, pick it from the **Runtime image** dropdown.
   Only `READY` runtime images appear — one still building has no image, and a failed one
   never will.

## Sharing

A runtime image starts private to whoever created it, and is shared the same two ways a
dataset or a model is — the **Share** button on its card opens the same modal.

**Named people.** Grant `READ` or `WRITE` by email. A `READ` grantee can start a computing
unit from the image and read the Dockerfile that produced it; a `WRITE` grantee can also
edit and rebuild it.

**Everyone.** Publishing makes the image startable by every Texera user. It does not make
the Dockerfile writable — publishing offers something to run, not something to rewrite.

Unlike `dataset.is_public` and `model.is_public`, which default to `TRUE`, a runtime image
defaults to **private**. An image is arbitrary instructions its owner wrote, so offering
other people something to execute is a decision to take rather than a state to start in.

Three limits are deliberate:

| Action | Who |
| --- | --- |
| Start a computing unit, read the Dockerfile, read the build log | owner, any grantee, anyone if public |
| Edit, rebuild, publish or unpublish | owner, `WRITE` grantee |
| Delete, and change who it is shared with | owner only |

**Delete is narrower than edit** because deleting takes the image away from everyone it
was shared with. A `WRITE` grantee was given the right to change a runtime image, not to
remove it from the people depending on it. Revoking your *own* grant is the exception that
needs no write access — that is how you drop a shared runtime image off your list.

A runtime image that is not yours is reported as **absent rather than forbidden**, so which
ids exist is not something a stranger can map by reading status codes.

Sharing reuses the machinery already behind datasets, models and computing units: the
`runtime_image_user_access` table mirrors `dataset_user_access`, and the endpoints live at
`/api/access/runtime-image/{list,grant,revoke,owner}` so the frontend's existing generic
`ShareAccessService` addresses them with no new client code.

## How a build runs

```
RuntimeImageResource ──creates──▶ ConfigMap (Dockerfile + buildkitd.toml)
                    ──creates──▶ Job (BuildKit, rootless)
                                    │ pulls base image
                                    ▼
                                 registry ◀── pushes <registry>/texera-runtime-image/<riid>:<n>
                                    │
                                    ▼
                          kubelet pulls it for the CU pod
```

**BuildKit, not Kaniko.** Google archived Kaniko in June 2025; BuildKit is what replaced
it. Rootless specifically, because a build executes arbitrary user-supplied instructions
and a privileged builder would make the cluster's isolation depend on the Dockerfile being
well behaved. Rootless costs an unconfined seccomp/AppArmor profile and nothing else.

**One registry address for two different resolvers.** The BuildKit job pushing an image is
a pod and resolves cluster DNS; the kubelet pulling it is not and does not. A ClusterIP is
the one address both can reach, so the registry Service pins one
(`runtimeImageRegistry.clusterIP`) rather than being assigned one — an image reference
embeds the address, so it has to survive a reinstall of the chart.

**The tag carries a build number.** A rebuild publishes `:<n+1>` rather than overwriting
`:<n>`, so a computing unit already running an earlier build is unaffected by a rebuild.

**Status is reconciled on read.** A build finishes on the cluster, not in the service, so
a row learns its outcome when someone lists or opens it. That keeps the feature free of
background threads and leader election; the UI polls while a build is in flight anyway.

## Setup

The base image has to be in the registry before any runtime image can build, because the
BuildKit job is a pod and cannot see images that exist only in the node's local daemon:

```bash
bin/k8s/push-base-image.sh [minikube-profile] [source-image]
```

Run it again whenever the engine image is rebuilt. Runtime images built before that keep
running the older base until they are rebuilt themselves.

## Configuration

| Value | Default | Purpose |
| --- | --- | --- |
| `runtimeImages.enabled` | `true` | Turns the feature and its API off entirely |
| `runtimeImages.builderImage` | `moby/buildkit:v0.18.2-rootless` | What runs the build |
| `runtimeImages.buildNamespace` | the computing-unit pool namespace | Where build jobs run |
| `runtimeImages.buildTimeoutSeconds` | `3600` | A build past this is killed |
| `runtimeImages.baseImage` | `<registry>/texera/computing-unit-master:dev` | Pre-filled `FROM`, and what user Dockerfiles are expected to build on |
| `runtimeImageRegistry.clusterIP` | `10.96.0.99` | Pinned; must be inside the Service CIDR the container runtime treats as insecure |
| `runtimeImageRegistry.persistence.enabled` | `false` | Off means built images are lost if the registry pod restarts |

## Known limits of this proof of concept

- **The registry is plain HTTP with no authentication.** It works only because container
  runtimes are configured to treat the Service CIDR as insecure. A real deployment
  terminates TLS and gives the pull a credential.
- **Nothing constrains what a Dockerfile may do.** A build runs arbitrary instructions with
  network access, and an image that omits `USER texera` runs as **root** in the computing-unit
  pod, because the pod spec sets no `securityContext` of its own. Before this is exposed to
  users who are not administrators, the pod spec should pin `runAsUser`/`runAsNonRoot` so
  the image cannot decide.
- **No quota on images or builds.** Nothing limits how many runtime images a user creates, how
  large an image may be, or how long the registry keeps old build tags.
- **Sharing widens the reach of the point above.** A published runtime image invites other
  users to start a unit from an image its owner wrote, and nothing constrains what that
  image does or which user it runs as. Pinning `runAsUser`/`runAsNonRoot` on the pod spec
  matters more once a runtime image can be public than it did when one was private.
- **The Dockerfile editor is a textarea**, not a syntax-highlighting editor.
- **Deleting a runtime image leaves its images in the registry.** The rows and build jobs go;
  the pushed layers stay.
