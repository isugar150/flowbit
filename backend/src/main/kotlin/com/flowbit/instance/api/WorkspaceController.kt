package com.flowbit.instance.api

import com.flowbit.instance.application.InstanceService
import com.flowbit.instance.application.Workspace
import com.flowbit.media.api.UploadReader
import com.flowbit.media.application.ImageStorage
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController

data class WorkspaceResponse(
    val name: String,
    val logoUrl: String?,
    val workspaceKey: String,
) {
    companion object {
        fun from(workspace: Workspace) = WorkspaceResponse(workspace.name, ImageStorage.urlOf(workspace.logoKey), workspace.workspaceKey)
    }
}

/** Only the fields present change. */
data class UpdateWorkspaceRequest(
    val name: String? = null,
    val workspaceKey: String? = null,
)

/** Read by every signed-in user to draw the app frame. */
@RestController
@RequestMapping("/api/v1/workspace")
class WorkspaceController(
    private val instanceService: InstanceService,
) {
    @GetMapping
    suspend fun workspace(): WorkspaceResponse = WorkspaceResponse.from(instanceService.workspace())
}

/** Everything under `/api/v1/admin` requires the ADMIN role (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/workspace")
class AdminWorkspaceController(
    private val instanceService: InstanceService,
    private val uploadReader: UploadReader,
) {
    @PatchMapping
    suspend fun update(
        @RequestBody body: UpdateWorkspaceRequest,
    ): WorkspaceResponse {
        body.name?.let { instanceService.rename(it) }
        body.workspaceKey?.let { instanceService.setWorkspaceKey(it) }
        return WorkspaceResponse.from(instanceService.workspace())
    }

    @PutMapping("/logo", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    suspend fun replaceLogo(
        @RequestPart("file") file: FilePart,
    ): WorkspaceResponse = WorkspaceResponse.from(instanceService.replaceLogo(uploadReader.read(file)))

    @DeleteMapping("/logo")
    suspend fun removeLogo(): WorkspaceResponse = WorkspaceResponse.from(instanceService.removeLogo())
}
