package dev.skw.application.port.out

import dev.skw.domain.relationship.Relationship

interface RelationshipRepository {
    fun save(relationship: Relationship): Relationship
}
