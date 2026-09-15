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

import { TestBed } from "@angular/core/testing";
import { HttpClientTestingModule, HttpTestingController } from "@angular/common/http/testing";

import { RUNTIME_IMAGE_BASE_URL, RuntimeImage, RuntimeImageService, isStartable } from "./runtime-image.service";
import { commonTestProviders } from "../../../../common/testing/test-utils";

function runtimeImage(overrides: Partial<RuntimeImage> = {}): RuntimeImage {
  return {
    riid: 1,
    name: "alphafold3",
    dockerfile: "FROM base\n",
    status: "READY",
    imageTag: "10.96.0.99:5000/texera-runtime-image/1:1",
    buildNumber: 1,
    creationTime: 0,
    updateTime: 0,
    isPublic: false,
    ownerEmail: "owner@texera.com",
    access: "OWNER",
    ...overrides,
  };
}

describe("isStartable", () => {
  it("accepts only a finished build", () => {
    // A computing unit started from anything else would have no image to pull.
    expect(isStartable(runtimeImage({ status: "READY" }))).toBe(true);
    expect(isStartable(runtimeImage({ status: "BUILDING" }))).toBe(false);
    expect(isStartable(runtimeImage({ status: "FAILED" }))).toBe(false);
    expect(isStartable(runtimeImage({ status: "PENDING" }))).toBe(false);
  });
});

describe("RuntimeImageService", () => {
  let service: RuntimeImageService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [RuntimeImageService, ...commonTestProviders],
    });
    service = TestBed.inject(RuntimeImageService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it("lists runtime images", () => {
    let received: RuntimeImage[] | undefined;
    service.list().subscribe(result => (received = result));

    const request = httpMock.expectOne(RUNTIME_IMAGE_BASE_URL);
    expect(request.request.method).toBe("GET");
    request.flush([runtimeImage()]);

    expect(received?.length).toBe(1);
    expect(received?.[0].name).toBe("alphafold3");
  });

  it("creates with the name and Dockerfile", () => {
    service.create("af3", "FROM base\nRUN true\n").subscribe();

    const request = httpMock.expectOne(RUNTIME_IMAGE_BASE_URL);
    expect(request.request.method).toBe("POST");
    expect(request.request.body).toEqual({ name: "af3", dockerfile: "FROM base\nRUN true\n" });
    request.flush(runtimeImage({ status: "BUILDING" }));
  });

  it("updates through PUT, which is what triggers a rebuild", () => {
    service.update(7, "af3", "FROM base\n").subscribe();

    const request = httpMock.expectOne(`${RUNTIME_IMAGE_BASE_URL}/7`);
    expect(request.request.method).toBe("PUT");
    request.flush(runtimeImage({ riid: 7 }));
  });

  it("rebuilds without a body", () => {
    service.rebuild(7).subscribe();

    const request = httpMock.expectOne(`${RUNTIME_IMAGE_BASE_URL}/7/rebuild`);
    expect(request.request.method).toBe("POST");
    request.flush(runtimeImage({ riid: 7 }));
  });

  it("reads the build log", () => {
    let log: string | undefined;
    service.logs(7).subscribe(result => (log = result.log));

    const request = httpMock.expectOne(`${RUNTIME_IMAGE_BASE_URL}/7/logs`);
    expect(request.request.method).toBe("GET");
    request.flush({ riid: 7, status: "READY", buildNumber: 2, log: "#1 DONE" });

    expect(log).toBe("#1 DONE");
  });

  it("fetches the default Dockerfile a new runtime image starts from", () => {
    let dockerfile: string | undefined;
    service.getDefaultDockerfile().subscribe(result => (dockerfile = result.dockerfile));

    const request = httpMock.expectOne(`${RUNTIME_IMAGE_BASE_URL}/default-dockerfile`);
    request.flush({ baseImage: "registry/base:dev", dockerfile: "FROM registry/base:dev\n" });

    expect(dockerfile).toContain("FROM registry/base:dev");
  });

  it("deletes by id", () => {
    service.delete(7).subscribe();

    const request = httpMock.expectOne(`${RUNTIME_IMAGE_BASE_URL}/7`);
    expect(request.request.method).toBe("DELETE");
    request.flush(null);
  });
});
