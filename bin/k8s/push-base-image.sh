#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Publishes the computing-unit image into the in-cluster runtime image registry.
#
# Runtime image Dockerfiles build FROM this image, and the BuildKit job that builds
# them is a pod: it cannot see images that exist only in the node's local daemon,
# so the base has to be in a registry it can pull from.
#
# Run once after installing the chart, and again whenever the engine image is
# rebuilt -- runtime images built before that keep running the older base until
# they are rebuilt themselves.
#
#   bin/k8s/push-base-image.sh [minikube-profile] [source-image]

set -euo pipefail

PROFILE="${1:-texera-mount}"
SOURCE_IMAGE="${2:-texera-local/texera-workflow-execution-coordinator:dev}"
TARGET_IMAGE="${TEXERA_RUNTIME_IMAGE_BASE_IMAGE:-10.96.0.99:5000/texera/computing-unit-master:dev}"

echo "Publishing $SOURCE_IMAGE as $TARGET_IMAGE"
echo

# Tagged and pushed from inside the node rather than from this host: the registry
# is a ClusterIP, which the node can route to and this host cannot, and the node's
# daemon already trusts the Service CIDR as an insecure registry.
minikube -p "$PROFILE" ssh -- \
  "docker tag $SOURCE_IMAGE $TARGET_IMAGE && docker push $TARGET_IMAGE"

echo
echo "Verifying the tag is readable from the registry..."
minikube -p "$PROFILE" ssh -- \
  "curl -sS -m 15 http://${TARGET_IMAGE%%/*}/v2/texera/computing-unit-master/tags/list"
echo
