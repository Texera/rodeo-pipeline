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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import type { TexeraClient } from "../client";

/** Mirrors RuntimeImageResource.Status. */
export type RuntimeImageStatus = "PENDING" | "BUILDING" | "READY" | "FAILED";

/** Mirrors RuntimeImageResource.Access. */
export type RuntimeImageAccess = "OWNER" | "WRITE" | "READ";

export interface RuntimeImage {
  riid: number;
  name: string;
  dockerfile: string;
  status: RuntimeImageStatus;
  /** Where the built image is pulled from. Null until a build first succeeds. */
  imageTag: string | null;
  buildNumber: number;
  creationTime: number;
  updateTime: number;
  isPublic: boolean;
  ownerEmail: string;
  access: RuntimeImageAccess;
}

/**
 * Every runtime image this account may start a computing unit from: its own, ones
 * shared with it, and public ones. The backend decides visibility, so a caller does
 * not have to ask for public ones separately.
 */
export async function listRuntimeImages(client: TexeraClient): Promise<RuntimeImage[]> {
  return client.request<RuntimeImage[]>("computingUnit", "/api/runtime-image");
}

export async function getRuntimeImage(client: TexeraClient, riid: number): Promise<RuntimeImage> {
  return client.request<RuntimeImage>("computingUnit", `/api/runtime-image/${riid}`);
}

/** Only a READY image has been built and can actually be started from. */
export function isRuntimeImageStartable(image: RuntimeImage): boolean {
  return image.status === "READY";
}
