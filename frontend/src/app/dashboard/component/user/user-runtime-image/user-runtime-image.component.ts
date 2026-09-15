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

import { Component, OnInit } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { DatePipe, NgFor, NgIf } from "@angular/common";
import { UntilDestroy, untilDestroyed } from "@ngneat/until-destroy";
import { timer } from "rxjs";
import { switchMap } from "rxjs/operators";

import { NzButtonComponent } from "ng-zorro-antd/button";
import { NzCardComponent } from "ng-zorro-antd/card";
import { NzIconDirective } from "ng-zorro-antd/icon";
import { NzInputDirective } from "ng-zorro-antd/input";
import { NzModalComponent, NzModalContentDirective, NzModalService } from "ng-zorro-antd/modal";
import { NzTagComponent } from "ng-zorro-antd/tag";
import { NzTooltipDirective } from "ng-zorro-antd/tooltip";

import { NotificationService } from "../../../../common/service/notification/notification.service";
import {
  RuntimeImage,
  RuntimeImageService,
  RuntimeImageStatus,
  isEditable,
  isOwned,
} from "../../../service/user/runtime-image/runtime-image.service";
import { ShareAccessComponent } from "../share-access/share-access.component";

/** Name rule, kept in step with RuntimeImageResource's server-side check. */
export function validateRuntimeImageName(name: string): string | null {
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(name) || name.length > 128) {
    return "Name must start with a letter or digit and contain only letters, digits, dots, hyphens and underscores.";
  }
  return null;
}

const BUILD_POLL_INTERVAL_MS = 3000;

@UntilDestroy()
@Component({
  selector: "texera-user-runtime-image",
  templateUrl: "./user-runtime-image.component.html",
  styleUrls: ["./user-runtime-image.component.scss"],
  imports: [
    NgIf,
    NgFor,
    DatePipe,
    FormsModule,
    NzButtonComponent,
    NzCardComponent,
    NzIconDirective,
    NzInputDirective,
    NzModalComponent,
    NzModalContentDirective,
    NzTagComponent,
    NzTooltipDirective,
  ],
  standalone: true,
})
export class UserRuntimeImageComponent implements OnInit {
  runtimeImages: RuntimeImage[] = [];
  isLoading = false;

  editorVisible = false;
  editorTitle = "";
  /** Set when editing an existing runtimeImage; undefined when creating one. */
  editingRiid?: number;
  draftName = "";
  draftDockerfile = "";
  isSaving = false;

  logsVisible = false;
  logsTitle = "";
  logsRiid?: number;
  logsText = "";
  logsStatus?: RuntimeImageStatus;
  isLoadingLogs = false;

  private defaultDockerfile = "";

  constructor(
    private runtimeImageService: RuntimeImageService,
    private notificationService: NotificationService,
    private modalService: NzModalService
  ) {}

  ngOnInit(): void {
    this.refresh();
    this.loadDefaultDockerfile();

    // A build runs on the cluster and finishes without telling anyone, so the list is
    // polled while one is in flight. It stops once nothing is building, rather than
    // polling forever on a page that is usually idle.
    timer(BUILD_POLL_INTERVAL_MS, BUILD_POLL_INTERVAL_MS)
      .pipe(
        switchMap(() => this.runtimeImageService.list()),
        untilDestroyed(this)
      )
      .subscribe({
        next: runtimeImages => {
          if (this.anyBuilding(this.runtimeImages) || this.anyBuilding(runtimeImages)) {
            this.runtimeImages = runtimeImages;
            if (this.logsVisible && this.logsRiid !== undefined) {
              this.loadLogs(this.logsRiid, false);
            }
          }
        },
        error: () => {
          // A failed poll is not worth a toast; the next tick will try again.
        },
      });
  }

  private anyBuilding(runtimeImages: RuntimeImage[]): boolean {
    return runtimeImages.some(runtimeImage => runtimeImage.status === "BUILDING");
  }

  private loadDefaultDockerfile(): void {
    this.runtimeImageService
      .getDefaultDockerfile()
      .pipe(untilDestroyed(this))
      .subscribe({
        next: response => (this.defaultDockerfile = response.dockerfile),
        error: () => {
          // Only affects what a new runtimeImage is pre-filled with, so an empty editor
          // is a survivable outcome and not worth interrupting the user for.
        },
      });
  }

  refresh(): void {
    this.isLoading = true;
    this.runtimeImageService
      .list()
      .pipe(untilDestroyed(this))
      .subscribe({
        next: runtimeImages => {
          this.runtimeImages = runtimeImages;
          this.isLoading = false;
        },
        error: (error: unknown) => {
          this.isLoading = false;
          this.notificationService.error(`Could not load runtime images: ${this.messageOf(error)}`);
        },
      });
  }

  onClickNew(): void {
    this.editingRiid = undefined;
    this.editorTitle = "New runtime image";
    this.draftName = "";
    // Pre-filled with the computing-unit image's own Dockerfile so the starting point is
    // what already exists, rather than a blank file the user has to guess the shape of.
    this.draftDockerfile = this.defaultDockerfile;
    this.editorVisible = true;
  }

  onClickEdit(runtimeImage: RuntimeImage): void {
    this.editingRiid = runtimeImage.riid;
    this.editorTitle = `Edit ${runtimeImage.name}`;
    this.draftName = runtimeImage.name;
    this.draftDockerfile = runtimeImage.dockerfile;
    this.editorVisible = true;
  }

  onClickSave(): void {
    const name = this.draftName.trim();
    const nameError = validateRuntimeImageName(name);
    if (nameError) {
      this.notificationService.error(nameError);
      return;
    }
    if (!this.draftDockerfile.trim()) {
      this.notificationService.error("Dockerfile cannot be empty.");
      return;
    }

    this.isSaving = true;
    const save =
      this.editingRiid === undefined
        ? this.runtimeImageService.create(name, this.draftDockerfile)
        : this.runtimeImageService.update(this.editingRiid, name, this.draftDockerfile);

    save.pipe(untilDestroyed(this)).subscribe({
      next: runtimeImage => {
        this.isSaving = false;
        this.editorVisible = false;
        this.notificationService.success(`Building '${runtimeImage.name}'. This takes a few minutes.`);
        this.refresh();
      },
      error: (error: unknown) => {
        this.isSaving = false;
        this.notificationService.error(`Could not save the runtime image: ${this.messageOf(error)}`);
      },
    });
  }

  onClickCancelEdit(): void {
    this.editorVisible = false;
  }

  onClickRebuild(runtimeImage: RuntimeImage): void {
    this.runtimeImageService
      .rebuild(runtimeImage.riid)
      .pipe(untilDestroyed(this))
      .subscribe({
        next: () => {
          this.notificationService.success(`Rebuilding '${runtimeImage.name}'.`);
          this.refresh();
        },
        error: (error: unknown) => this.notificationService.error(`Could not rebuild: ${this.messageOf(error)}`),
      });
  }

  onClickLogs(runtimeImage: RuntimeImage): void {
    this.logsRiid = runtimeImage.riid;
    this.logsTitle = `Build log — ${runtimeImage.name}`;
    this.logsText = "";
    this.logsVisible = true;
    this.loadLogs(runtimeImage.riid, true);
  }

  private loadLogs(riid: number, showSpinner: boolean): void {
    if (showSpinner) {
      this.isLoadingLogs = true;
    }
    this.runtimeImageService
      .logs(riid)
      .pipe(untilDestroyed(this))
      .subscribe({
        next: response => {
          this.isLoadingLogs = false;
          this.logsText = response.log || "(no output yet)";
          this.logsStatus = response.status;
        },
        error: (error: unknown) => {
          this.isLoadingLogs = false;
          this.logsText = `Could not read the build log: ${this.messageOf(error)}`;
        },
      });
  }

  onClickCloseLogs(): void {
    this.logsVisible = false;
    this.logsRiid = undefined;
  }

  onClickDelete(runtimeImage: RuntimeImage): void {
    this.modalService.confirm({
      nzTitle: `Delete '${runtimeImage.name}'?`,
      nzContent: "Computing units already started from this runtime image keep running; new ones cannot use it.",
      nzOkText: "Delete",
      nzOkDanger: true,
      nzOnOk: () =>
        this.runtimeImageService
          .delete(runtimeImage.riid)
          .pipe(untilDestroyed(this))
          .subscribe({
            next: () => {
              this.notificationService.success(`Deleted '${runtimeImage.name}'.`);
              this.refresh();
            },
            error: (error: unknown) => this.notificationService.error(`Could not delete: ${this.messageOf(error)}`),
          }),
    });
  }

  statusColor(status: RuntimeImageStatus): string {
    switch (status) {
      case "READY":
        return "green";
      case "BUILDING":
        return "blue";
      case "FAILED":
        return "red";
      default:
        return "default";
    }
  }

  /** Editing, rebuilding and publishing. Deleting additionally requires ownership. */
  canEdit(runtimeImage: RuntimeImage): boolean {
    return isEditable(runtimeImage);
  }

  canDelete(runtimeImage: RuntimeImage): boolean {
    return isOwned(runtimeImage);
  }

  /** Only the owner decides who else may use it. */
  canShare(runtimeImage: RuntimeImage): boolean {
    return isOwned(runtimeImage);
  }

  onClickShare(runtimeImage: RuntimeImage): void {
    const modal = this.modalService.create({
      nzContent: ShareAccessComponent,
      nzData: {
        type: "runtime-image",
        id: runtimeImage.riid,
        allOwners: this.runtimeImages.map(e => e.ownerEmail).filter((email, i, all) => all.indexOf(email) === i),
        inWorkspace: false,
      },
      nzFooter: null,
      nzTitle: `Share "${runtimeImage.name}"`,
    });
    // Publishing happens inside the modal, so the card's Public tag is stale until the
    // list is read again.
    modal.afterClose.pipe(untilDestroyed(this)).subscribe(() => this.refresh());
  }

  trackByRiid(_index: number, runtimeImage: RuntimeImage): number {
    return runtimeImage.riid;
  }

  private messageOf(error: unknown): string {
    const body = (error as { error?: { message?: string } })?.error;
    return body?.message ?? (error as { message?: string })?.message ?? "unknown error";
  }
}
