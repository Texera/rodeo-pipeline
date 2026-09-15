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
import { NoopAnimationsModule } from "@angular/platform-browser/animations";
import { HttpClientTestingModule } from "@angular/common/http/testing";
import { NZ_MODAL_DATA, NzModalRef } from "ng-zorro-antd/modal";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { WelcomeModalComponent } from "./welcome-modal.component";
import { NotificationService } from "../../service/notification/notification.service";
import { AuthService } from "../../service/user/auth.service";
import { commonTestProviders } from "../../testing/test-utils";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.bbbbbbCCCCCC";

describe("WelcomeModalComponent", () => {
  let component: WelcomeModalComponent;
  let modalRef: { close: ReturnType<typeof vi.fn> };
  let notification: { success: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    TestBed.resetTestingModule();
    vi.spyOn(AuthService, "getAccessToken").mockReturnValue(TOKEN);
    modalRef = { close: vi.fn() };
    notification = { success: vi.fn(), info: vi.fn() };

    TestBed.configureTestingModule({
      imports: [WelcomeModalComponent, NoopAnimationsModule, HttpClientTestingModule],
      providers: [
        { provide: NZ_MODAL_DATA, useValue: { userName: "alice" } },
        { provide: NzModalRef, useValue: modalRef },
        { provide: NotificationService, useValue: notification },
        ...commonTestProviders,
      ],
    });
    component = TestBed.createComponent(WelcomeModalComponent).componentInstance;
  });

  it("opens on the overview, not the setup steps", () => {
    expect(component.step).toBe(0);
    expect(component.userName).toBe("alice");
  });

  it("masks the token by default and reveals the real one on request", () => {
    expect(component.maskedToken).not.toBe(TOKEN);
    expect(component.maskedToken).toContain("•");
    // Enough of the ends survive that a user can tell two tokens apart.
    expect(component.maskedToken.startsWith(TOKEN.slice(0, 12))).toBe(true);
    expect(component.maskedToken.endsWith(TOKEN.slice(-6))).toBe(true);

    component.toggleToken();
    expect(component.tokenRevealed).toBe(true);
  });

  it("puts the real token in the copied configuration even while the screen shows a masked one", () => {
    // The whole point of the dialog: what gets pasted has to work.
    expect(component.configSnippet).toContain(TOKEN);
    expect(component.displayedConfigSnippet).not.toContain(TOKEN);

    component.toggleToken();
    expect(component.displayedConfigSnippet).toContain(TOKEN);
  });

  it("builds a configuration pointing at this deployment", () => {
    const config = JSON.parse(component.configSnippet);
    expect(config.mcpServers.texera.command).toBe("npx");
    expect(config.mcpServers.texera.args).toEqual(["-y", "@texera/mcp"]);
    expect(config.mcpServers.texera.env.TEXERA_BASE_URL).toBe(window.location.origin);
    expect(config.mcpServers.texera.env.TEXERA_TOKEN).toBe(TOKEN);
  });

  it("moves between the overview and the setup steps", () => {
    component.showSetup();
    expect(component.step).toBe(1);
    component.back();
    expect(component.step).toBe(0);
  });

  it("closes through the modal reference", () => {
    component.close();
    expect(modalRef.close).toHaveBeenCalled();
  });

  it("reveals the token when the clipboard is unavailable, so the user can still select it", async () => {
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: vi.fn().mockRejectedValue(new Error("denied")) },
      configurable: true,
    });

    component.copyToken();
    await Promise.resolve();
    await Promise.resolve();

    // Plain http is not a secure context, which is exactly where a self-hosted
    // deployment lives; a dead Copy button would leave the user stuck.
    expect(component.tokenRevealed).toBe(true);
  });
});
