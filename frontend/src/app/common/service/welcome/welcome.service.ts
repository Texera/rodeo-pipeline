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

import { Injectable } from "@angular/core";
import { NzModalService } from "ng-zorro-antd/modal";
import { WelcomeModalComponent } from "../../component/welcome-modal/welcome-modal.component";

@Injectable({ providedIn: "root" })
export class WelcomeService {
  constructor(private modalService: NzModalService) {}

  /**
   * Opens the welcome dialog. Shown on every sign-in rather than only the first:
   * it is also the way to get an account token for an MCP client, and someone
   * whose token has expired needs it again without hunting for a menu item.
   */
  open(userName: string): void {
    this.modalService.create({
      nzContent: WelcomeModalComponent,
      nzData: { userName },
      nzFooter: null,
      nzWidth: "640px",
      nzCentered: true,
      nzMaskClosable: false,
      // The dialog supplies its own heading, so the frame stays out of the way.
      nzTitle: undefined,
      nzClosable: true,
      nzBodyStyle: { padding: "24px" },
    });
  }
}
