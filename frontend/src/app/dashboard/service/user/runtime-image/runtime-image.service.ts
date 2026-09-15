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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Injectable } from "@angular/core";
import { HttpClient } from "@angular/common/http";
import { Observable } from "rxjs";
import { AppSettings } from "../../../../common/app-setting";

export const RUNTIME_IMAGE_BASE_URL = `${AppSettings.getApiEndpoint()}/runtime-image`;

/** Mirrors RuntimeImageResource.Status. */
export type RuntimeImageStatus = "PENDING" | "BUILDING" | "READY" | "FAILED";

/** Mirrors RuntimeImageResource.Access. Ownership is not a privilege, but the UI needs
 * the three cases apart, so the backend reports it alongside them. */
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
  /** Public means anyone may start a computing unit from the image, not edit it. */
  isPublic: boolean;
  ownerEmail: string;
  /** How the signed-in user reaches this one. */
  access: RuntimeImageAccess;
}

export function isOwned(runtimeImage: RuntimeImage): boolean {
  return runtimeImage.access === "OWNER";
}

/** Editing, rebuilding and publishing; deleting additionally requires ownership. */
export function isEditable(runtimeImage: RuntimeImage): boolean {
  return runtimeImage.access === "OWNER" || runtimeImage.access === "WRITE";
}

export interface RuntimeImageBuildLog {
  riid: number;
  status: RuntimeImageStatus;
  buildNumber: number;
  log: string;
}

export interface DefaultDockerfile {
  baseImage: string;
  dockerfile: string;
}

/** A computing unit can only be started from a runtime image in this state. */
export function isStartable(runtimeImage: RuntimeImage): boolean {
  return runtimeImage.status === "READY";
}

@Injectable({ providedIn: "root" })
export class RuntimeImageService {
  constructor(private http: HttpClient) {}

  list(): Observable<RuntimeImage[]> {
    return this.http.get<RuntimeImage[]>(RUNTIME_IMAGE_BASE_URL);
  }

  get(riid: number): Observable<RuntimeImage> {
    return this.http.get<RuntimeImage>(`${RUNTIME_IMAGE_BASE_URL}/${riid}`);
  }

  /** What a new runtime image's editor starts from — the computing-unit image itself. */
  getDefaultDockerfile(): Observable<DefaultDockerfile> {
    return this.http.get<DefaultDockerfile>(`${RUNTIME_IMAGE_BASE_URL}/default-dockerfile`);
  }

  /** Creating a runtime image starts its first build; the response is already BUILDING. */
  create(name: string, dockerfile: string): Observable<RuntimeImage> {
    return this.http.post<RuntimeImage>(RUNTIME_IMAGE_BASE_URL, { name, dockerfile });
  }

  /** Editing rebuilds: the image is whatever the Dockerfile says it is. */
  update(riid: number, name: string, dockerfile: string): Observable<RuntimeImage> {
    return this.http.put<RuntimeImage>(`${RUNTIME_IMAGE_BASE_URL}/${riid}`, { name, dockerfile });
  }

  rebuild(riid: number): Observable<RuntimeImage> {
    return this.http.post<RuntimeImage>(`${RUNTIME_IMAGE_BASE_URL}/${riid}/rebuild`, {});
  }

  /**
   * The build's output. Readable at any point, including long after the build finished —
   * which is the whole reason it is persisted rather than streamed.
   */
  logs(riid: number): Observable<RuntimeImageBuildLog> {
    return this.http.get<RuntimeImageBuildLog>(`${RUNTIME_IMAGE_BASE_URL}/${riid}/logs`);
  }

  delete(riid: number): Observable<void> {
    return this.http.delete<void>(`${RUNTIME_IMAGE_BASE_URL}/${riid}`);
  }

  /**
   * Flips public/private, mirroring the dataset and model endpoints the share modal
   * already drives. Publishing offers the built image to everyone; it does not hand out
   * the Dockerfile for anyone to change.
   */
  updateRuntimeImagePublicity(riid: number): Observable<RuntimeImage> {
    return this.http.post<RuntimeImage>(`${RUNTIME_IMAGE_BASE_URL}/${riid}/update/publicity`, {});
  }
}
