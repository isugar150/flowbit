package com.flowbit.user.domain

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

interface UserRepository : CoroutineCrudRepository<User, UUID> {
    /** Reads the user and holds a row lock until the surrounding transaction ends. */
    @Query("select * from users where login_id = :loginId for update")
    suspend fun findByLoginIdForUpdate(loginId: String): User?
}
