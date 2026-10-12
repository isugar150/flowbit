package com.flowbit.label

import com.flowbit.common.UuidV7
import com.flowbit.common.error.ValidationException
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.annotation.Id
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** A workspace-wide label that issues can carry, such as "버그" (V10__issue_labels.sql). */
@Table("labels")
class Label(
    @Id
    val id: UUID,
    val name: String,
    /** `#rrggbb`, always one of [LabelService.PALETTE]. */
    val color: String,
    val createdAt: Instant,
) {
    companion object {
        const val NAME_MAX_LENGTH = 30
    }
}

interface LabelRepository : CoroutineCrudRepository<Label, UUID> {
    @Query("select * from labels order by created_at, name")
    suspend fun findAllInOrder(): List<Label>

    @Query("select * from labels where lower(name) = lower(:name)")
    suspend fun findByNameIgnoreCase(name: String): Label?
}

@Service
class LabelService(
    private val repository: LabelRepository,
    private val entityTemplate: R2dbcEntityTemplate,
    private val clock: Clock,
) {
    suspend fun list(): List<Label> = repository.findAllInOrder()

    /**
     * Adds a label, or returns the one that already has this name (ignoring case): asking for a
     * label that exists is what happens when two people add the same one.
     */
    suspend fun create(rawName: String): Label {
        val name = rawName.trim()
        if (name.isEmpty()) throw ValidationException("name", "REQUIRED", "라벨 이름을 입력하세요")
        if (name.length > Label.NAME_MAX_LENGTH) {
            throw ValidationException("name", "TOO_LONG", "라벨 이름은 ${Label.NAME_MAX_LENGTH}자 이하여야 합니다")
        }
        repository.findByNameIgnoreCase(name)?.let { return it }
        val now = clock.instant()
        // Colours are handed out in turn, so new labels look different from their neighbours.
        val color = PALETTE[(repository.count() % PALETTE.size).toInt()]
        return try {
            entityTemplate.insert(Label(UuidV7.generate(now), name, color, now)).awaitSingle()
        } catch (_: DuplicateKeyException) {
            checkNotNull(repository.findByNameIgnoreCase(name))
        }
    }

    companion object {
        val PALETTE = listOf("#e5484d", "#8e6ee0", "#f2994a", "#3e8ee0", "#30a46c", "#d6409f", "#c9a400", "#8b8d98")
    }
}

data class LabelResponse(
    val id: UUID,
    val name: String,
    val color: String,
) {
    companion object {
        fun from(label: Label) = LabelResponse(label.id, label.name, label.color)
    }
}

data class CreateLabelRequest(
    val name: String,
)

/** Labels belong to the whole workspace: every signed-in user sees them and may add one. */
@RestController
@RequestMapping("/api/v1/labels")
class LabelController(
    private val labelService: LabelService,
) {
    @GetMapping
    suspend fun list(): List<LabelResponse> = labelService.list().map(LabelResponse::from)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @RequestBody body: CreateLabelRequest,
    ): LabelResponse = LabelResponse.from(labelService.create(body.name))
}
