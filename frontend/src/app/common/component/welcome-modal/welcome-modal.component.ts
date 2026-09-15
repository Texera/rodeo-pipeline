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

import { Component, inject } from "@angular/core";
import { NgIf } from "@angular/common";
import { NZ_MODAL_DATA, NzModalRef } from "ng-zorro-antd/modal";
import { NzButtonComponent } from "ng-zorro-antd/button";
import { NzIconDirective } from "ng-zorro-antd/icon";
import { NzWaveDirective } from "ng-zorro-antd/core/wave";
import { ɵNzTransitionPatchDirective } from "ng-zorro-antd/core/transition-patch";
import { NzTooltipDirective } from "ng-zorro-antd/tooltip";
import { AuthService } from "../../service/user/auth.service";
import { NotificationService } from "../../service/notification/notification.service";

/**
 * Shown once after signing in: what the assistant can do, and how to point a
 * chatbot of the user's own at this account over MCP.
 *
 * The token step is the reason this exists as a dialog rather than a docs page.
 * A user's JWT is in their browser and nowhere else, so the alternative is
 * telling them to open the developer console and read localStorage -- which is
 * both unfriendly and exactly the instruction a phishing page would give.
 */
@Component({
  selector: "texera-welcome-modal",
  templateUrl: "./welcome-modal.component.html",
  styleUrls: ["./welcome-modal.component.scss"],
  standalone: true,
  imports: [NgIf, NzButtonComponent, NzIconDirective, NzWaveDirective, ɵNzTransitionPatchDirective, NzTooltipDirective],
})
export class WelcomeModalComponent {
  readonly nzModalData = inject(NZ_MODAL_DATA, { optional: true }) ?? {};
  readonly userName: string = this.nzModalData.userName ?? "";

  /** 0 = what you can do, 1 = how to connect your own chatbot. */
  step = 0;

  tokenRevealed = false;

  constructor(
    private modalRef: NzModalRef,
    private notificationService: NotificationService
  ) {}

  get token(): string {
    return AuthService.getAccessToken() ?? "";
  }

  /** Enough to recognise it, not enough to use it if someone is looking over a shoulder. */
  get maskedToken(): string {
    const token = this.token;
    if (token.length <= 24) return token;
    return `${token.slice(0, 12)}${"•".repeat(24)}${token.slice(-6)}`;
  }

  get baseUrl(): string {
    return window.location.origin;
  }

  get configSnippet(): string {
    return JSON.stringify(
      {
        mcpServers: {
          texera: {
            command: "npx",
            args: ["-y", "@texera/mcp"],
            env: { TEXERA_BASE_URL: this.baseUrl, TEXERA_TOKEN: this.token },
          },
        },
      },
      null,
      2
    );
  }

  /** The same snippet with the token replaced, for showing on screen. */
  get displayedConfigSnippet(): string {
    return this.tokenRevealed ? this.configSnippet : this.configSnippet.replace(this.token, this.maskedToken);
  }

  showSetup(): void {
    this.step = 1;
  }

  back(): void {
    this.step = 0;
  }

  close(): void {
    this.modalRef.close();
  }

  toggleToken(): void {
    this.tokenRevealed = !this.tokenRevealed;
  }

  copyToken(): void {
    void this.copy(this.token, "Token copied. Paste it as TEXERA_TOKEN.");
  }

  copyConfig(): void {
    void this.copy(this.configSnippet, "Configuration copied, token included.");
  }

  private async copy(text: string, message: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      this.notificationService.success(message);
    } catch {
      // Clipboard access is denied outside a secure context, which a
      // self-hosted deployment on plain http is. Revealing the token lets the
      // user select it by hand rather than leaving them with a dead button.
      this.tokenRevealed = true;
      this.notificationService.info("Copying was blocked by the browser — the text is now shown so you can select it.");
    }
  }
}
