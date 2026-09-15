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
import { NzModalService } from "ng-zorro-antd/modal";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { WelcomeService } from "./welcome.service";
import { WelcomeModalComponent } from "../../component/welcome-modal/welcome-modal.component";

describe("WelcomeService", () => {
  let service: WelcomeService;
  let modalService: { create: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    TestBed.resetTestingModule();
    modalService = { create: vi.fn() };
    TestBed.configureTestingModule({
      providers: [WelcomeService, { provide: NzModalService, useValue: modalService }],
    });
    service = TestBed.inject(WelcomeService);
  });

  it("opens the welcome dialog with the account's name", () => {
    service.open("alice");

    expect(modalService.create).toHaveBeenCalledTimes(1);
    const config = modalService.create.mock.calls[0][0];
    expect(config.nzContent).toBe(WelcomeModalComponent);
    expect(config.nzData).toEqual({ userName: "alice" });
  });

  it("opens again on every sign-in rather than only the first", () => {
    // The dialog is also how an account token is obtained, so someone whose
    // token expired has to be able to reach it by signing in again.
    service.open("alice");
    service.open("alice");
    service.open("bob");

    expect(modalService.create).toHaveBeenCalledTimes(3);
  });

  it("remembers nothing between sign-ins", () => {
    // No stored flag means nothing to go stale, and nothing to clear when a
    // second person uses the same browser.
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    service.open("alice");
    expect(setItem).not.toHaveBeenCalled();
    setItem.mockRestore();
  });
});
