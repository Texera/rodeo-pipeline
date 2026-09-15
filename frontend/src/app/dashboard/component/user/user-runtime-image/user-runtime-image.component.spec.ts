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

import { ComponentFixture, TestBed } from "@angular/core/testing";
import { BrowserAnimationsModule } from "@angular/platform-browser/animations";
import { HttpClientTestingModule } from "@angular/common/http/testing";
import { Observable, of, throwError } from "rxjs";

import { UserRuntimeImageComponent, validateRuntimeImageName } from "./user-runtime-image.component";
import { RuntimeImage, RuntimeImageService } from "../../../service/user/runtime-image/runtime-image.service";
import { NzModalService } from "ng-zorro-antd/modal";

import { NotificationService } from "../../../../common/service/notification/notification.service";
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

describe("validateRuntimeImageName", () => {
  it("accepts the names an image tag can carry", () => {
    expect(validateRuntimeImageName("alphafold3")).toBeNull();
    expect(validateRuntimeImageName("af3.cpu_v2-final")).toBeNull();
  });

  it("rejects names the server would reject", () => {
    // Leading punctuation, spaces and slashes all break the tag or the object name.
    expect(validateRuntimeImageName("-leading")).not.toBeNull();
    expect(validateRuntimeImageName("has space")).not.toBeNull();
    expect(validateRuntimeImageName("has/slash")).not.toBeNull();
    expect(validateRuntimeImageName("")).not.toBeNull();
  });
});

describe("UserRuntimeImageComponent", () => {
  let list: ReturnType<typeof vi.fn>;
  let create: ReturnType<typeof vi.fn>;
  let update: ReturnType<typeof vi.fn>;
  let rebuild: ReturnType<typeof vi.fn>;
  let logs: ReturnType<typeof vi.fn>;
  let notifyError: ReturnType<typeof vi.fn>;
  let notifySuccess: ReturnType<typeof vi.fn>;
  let defaultDockerfile: Observable<unknown>;

  async function createFixture(): Promise<ComponentFixture<UserRuntimeImageComponent>> {
    list = vi.fn().mockReturnValue(of([]));
    create = vi.fn().mockReturnValue(of(runtimeImage({ status: "BUILDING" })));
    update = vi.fn().mockReturnValue(of(runtimeImage({ status: "BUILDING" })));
    rebuild = vi.fn().mockReturnValue(of(runtimeImage({ status: "BUILDING" })));
    logs = vi.fn().mockReturnValue(of({ riid: 1, status: "READY", buildNumber: 1, log: "#1 DONE" }));
    notifyError = vi.fn();
    notifySuccess = vi.fn();
    defaultDockerfile = of({ baseImage: "registry/base:dev", dockerfile: "FROM registry/base:dev\n" });

    await TestBed.configureTestingModule({
      imports: [UserRuntimeImageComponent, BrowserAnimationsModule, HttpClientTestingModule],
      providers: [
        {
          provide: RuntimeImageService,
          useValue: {
            list,
            create,
            update,
            rebuild,
            logs,
            delete: vi.fn().mockReturnValue(of(undefined)),
            getDefaultDockerfile: () => defaultDockerfile,
          },
        },
        { provide: NotificationService, useValue: { success: notifySuccess, error: notifyError } },
        { provide: NzModalService, useValue: { confirm: vi.fn() } },
        ...commonTestProviders,
      ],
    }).compileComponents();

    return TestBed.createComponent(UserRuntimeImageComponent);
  }

  it("pre-fills a new runtimeImage with the computing-unit image's own Dockerfile", async () => {
    const fixture = await createFixture();
    fixture.detectChanges();

    fixture.componentInstance.onClickNew();

    // The point of showing it is that the user can see what already exists before
    // deciding what to add, so an empty editor would defeat the feature.
    expect(fixture.componentInstance.draftDockerfile).toContain("FROM registry/base:dev");
    expect(fixture.componentInstance.editingRiid).toBeUndefined();
  });

  it("loads an existing runtimeImage's own Dockerfile when editing", async () => {
    const fixture = await createFixture();
    fixture.detectChanges();

    fixture.componentInstance.onClickEdit(runtimeImage({ riid: 4, dockerfile: "FROM base\nRUN x\n" }));

    expect(fixture.componentInstance.editingRiid).toBe(4);
    expect(fixture.componentInstance.draftDockerfile).toBe("FROM base\nRUN x\n");
  });

  it("creates when there is no runtimeImage being edited", async () => {
    const fixture = await createFixture();
    fixture.detectChanges();
    const component = fixture.componentInstance;

    component.onClickNew();
    component.draftName = "af3";
    component.draftDockerfile = "FROM base\n";
    component.onClickSave();

    expect(create).toHaveBeenCalledWith("af3", "FROM base\n");
    expect(update).not.toHaveBeenCalled();
  });

  it("updates when editing, because saving an edit is what rebuilds", async () => {
    const fixture = await createFixture();
    fixture.detectChanges();
    const component = fixture.componentInstance;

    component.onClickEdit(runtimeImage({ riid: 4 }));
    component.draftDockerfile = "FROM base\nRUN new\n";
    component.onClickSave();

    expect(update).toHaveBeenCalledWith(4, "alphafold3", "FROM base\nRUN new\n");
    expect(create).not.toHaveBeenCalled();
  });

  it("refuses an invalid name without calling the backend", async () => {
    const fixture = await createFixture();
    fixture.detectChanges();
    const component = fixture.componentInstance;

    component.onClickNew();
    component.draftName = "has space";
    component.draftDockerfile = "FROM base\n";
    component.onClickSave();

    expect(create).not.toHaveBeenCalled();
    expect(notifyError).toHaveBeenCalled();
  });

  it("opens the log for an runtimeImage whose build finished long ago", async () => {
    const fixture = await createFixture();
    fixture.detectChanges();
    const component = fixture.componentInstance;

    component.onClickLogs(runtimeImage({ riid: 4, status: "READY" }));

    // Readable at any point, which is the whole reason the log is persisted rather than
    // only streamed while the build runs.
    expect(logs).toHaveBeenCalledWith(4);
    expect(component.logsVisible).toBe(true);
    expect(component.logsText).toBe("#1 DONE");
  });

  it("still renders when the default Dockerfile cannot be fetched", async () => {
    const fixture = await createFixture();
    defaultDockerfile = throwError(() => new Error("unavailable"));
    fixture.detectChanges();

    fixture.componentInstance.onClickNew();

    expect(fixture.componentInstance.editorVisible).toBe(true);
    expect(notifyError).not.toHaveBeenCalled();
  });

  it("colours status so a failed build is not mistaken for a finished one", async () => {
    const fixture = await createFixture();
    fixture.detectChanges();
    const component = fixture.componentInstance;

    expect(component.statusColor("READY")).toBe("green");
    expect(component.statusColor("BUILDING")).toBe("blue");
    expect(component.statusColor("FAILED")).toBe("red");
  });

  it("offers every action on an runtimeImage the user owns", async () => {
    const fixture = await createFixture();
    fixture.detectChanges();
    const component = fixture.componentInstance;
    const owned = runtimeImage({ access: "OWNER" });

    expect(component.canEdit(owned)).toBe(true);
    expect(component.canDelete(owned)).toBe(true);
    expect(component.canShare(owned)).toBe(true);
  });

  it("offers nothing but use on an runtimeImage shared read-only", async () => {
    const fixture = await createFixture();
    fixture.detectChanges();
    const component = fixture.componentInstance;
    const readOnly = runtimeImage({ access: "READ", ownerEmail: "someone@else.com" });

    expect(component.canEdit(readOnly)).toBe(false);
    expect(component.canDelete(readOnly)).toBe(false);
    expect(component.canShare(readOnly)).toBe(false);
  });

  it("lets a WRITE grantee edit but neither delete nor reshare", async () => {
    const fixture = await createFixture();
    fixture.detectChanges();
    const component = fixture.componentInstance;
    const writable = runtimeImage({ access: "WRITE", ownerEmail: "someone@else.com" });

    // Deleting would take the image away from everyone else it was shared with, and
    // deciding who else may use it stays with the owner.
    expect(component.canEdit(writable)).toBe(true);
    expect(component.canDelete(writable)).toBe(false);
    expect(component.canShare(writable)).toBe(false);
  });

  it("re-reads the list after the share modal closes", async () => {
    const afterClose = of(undefined);
    const create = vi.fn().mockReturnValue({ afterClose });
    const fixture = await createFixture();
    (fixture.debugElement.injector.get(NzModalService) as unknown as { create: unknown }).create = create;
    fixture.detectChanges();

    const callsBefore = list.mock.calls.length;
    fixture.componentInstance.onClickShare(runtimeImage());

    expect(create).toHaveBeenCalled();
    const config = create.mock.calls[0][0] as { nzData: { type: string; id: number } };
    expect(config.nzData.type).toBe("runtime-image");
    expect(config.nzData.id).toBe(1);
    // Publishing happens inside the modal, so the card's Public tag would be stale
    // without this.
    expect(list.mock.calls.length).toBeGreaterThan(callsBefore);
  });
});
