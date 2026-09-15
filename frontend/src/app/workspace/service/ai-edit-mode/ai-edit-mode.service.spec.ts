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

import { TestBed } from "@angular/core/testing";
import { AiEditModeService } from "./ai-edit-mode.service";

describe("AiEditModeService", () => {
  let service: AiEditModeService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [AiEditModeService] });
    service = TestBed.inject(AiEditModeService);
  });

  it("starts inactive", () => {
    expect(service.isActive()).toBe(false);
  });

  it("toggles on and back off", () => {
    service.toggle();
    expect(service.isActive()).toBe(true);
    service.toggle();
    expect(service.isActive()).toBe(false);
  });

  it("exits when active", () => {
    service.toggle();
    service.exit();
    expect(service.isActive()).toBe(false);
  });

  it("emits the current state to late subscribers", () => {
    service.toggle();
    const seen: boolean[] = [];
    service.getActiveStream().subscribe(active => seen.push(active));
    expect(seen).toEqual([true]);
  });

  it("emits on every change", () => {
    const seen: boolean[] = [];
    service.getActiveStream().subscribe(active => seen.push(active));
    service.toggle();
    service.exit();
    expect(seen).toEqual([false, true, false]);
  });

  it("does not emit when exiting while already inactive", () => {
    const seen: boolean[] = [];
    service.getActiveStream().subscribe(active => seen.push(active));
    service.exit();
    expect(seen).toEqual([false]);
  });
});
