package com.flowbit.project.application

/**
 * The SQL rule for "may enter this project", shared by every query that filters by it so that a
 * project open to everyone (D-192) is open to all of them alike.
 */
object ProjectAccessSql {
    /**
     * A condition that holds when the person is a direct member of the project or the project is open
     * to every member of the workspace. [project] and [user] are SQL expressions (a column or a
     * `:parameter`); the person's own account being active is for the caller to check.
     */
    fun mayEnter(
        project: String,
        user: String,
    ): String =
        "(exists (select 1 from project_members pm_in where pm_in.project_id = $project and pm_in.user_id = $user) " +
            "or exists (select 1 from projects op_in where op_in.id = $project and op_in.open_to_all))"
}
