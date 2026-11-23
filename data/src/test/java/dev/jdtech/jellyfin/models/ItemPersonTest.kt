package dev.jdtech.jellyfin.models

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ItemPersonTest {
    private val tomKane = "0bc3e80a-9daa-0c55-1914-59f19cd90460".toItemId()
    private val mattLanter = "11111111-1111-1111-1111-111111111111".toItemId()
    private val ashleyEckstein = "22222222-2222-2222-2222-222222222222".toItemId()

    private fun person(
        id: ItemId,
        role: String,
        type: PersonKind = PersonKind.ACTOR,
        name: String = "Person $id",
    ) =
        ItemPerson(
            id = id,
            name = name,
            type = type,
            role = role,
            image = ItemPersonImage(uri = null, blurHash = null),
        )

    @Test
    fun `no duplicates returns a list equal to the input`() {
        val people =
            listOf(
                person(mattLanter, "Anakin Skywalker"),
                person(ashleyEckstein, "Ahsoka Tano"),
                person(tomKane, "Dave Filoni", type = PersonKind.DIRECTOR),
            )

        assertEquals(people, people.mergeDuplicateRoles())
    }

    @Test
    fun `same actor with three roles is merged into one entry with roles in order`() {
        val people =
            listOf(
                person(tomKane, "Yoda", name = "Tom Kane"),
                person(tomKane, "Narrator", name = "Tom Kane (duplicate)"),
                person(tomKane, "Admiral Yularen"),
            )

        val merged = people.mergeDuplicateRoles()

        assertEquals(1, merged.size)
        assertEquals(tomKane, merged[0].id)
        assertEquals("Tom Kane", merged[0].name)
        assertEquals(people[0].image, merged[0].image)
        assertEquals("Yoda / Narrator / Admiral Yularen", merged[0].role)
    }

    @Test
    fun `duplicate and blank roles are dropped`() {
        val people = listOf(person(tomKane, "Yoda"), person(tomKane, ""), person(tomKane, "Yoda"))

        val merged = people.mergeDuplicateRoles()

        assertEquals(1, merged.size)
        assertEquals("Yoda", merged[0].role)
    }

    @Test
    fun `same id as actor and director keeps both entries`() {
        val people =
            listOf(
                person(tomKane, "Yoda", type = PersonKind.ACTOR),
                person(tomKane, "", type = PersonKind.DIRECTOR),
            )

        val merged = people.mergeDuplicateRoles()

        assertEquals(2, merged.size)
        assertEquals(PersonKind.ACTOR, merged[0].type)
        assertEquals(PersonKind.DIRECTOR, merged[1].type)
        assertEquals(1, merged.count { it.type == PersonKind.ACTOR })
        assertEquals(1, merged.count { it.type == PersonKind.DIRECTOR })
    }

    @Test
    fun `order of first appearance is kept when duplicates are interleaved`() {
        val people =
            listOf(
                person(mattLanter, "Anakin Skywalker"),
                person(tomKane, "Yoda"),
                person(ashleyEckstein, "Ahsoka Tano"),
                person(tomKane, "Narrator"),
                person(mattLanter, "Clone Trooper"),
            )

        val merged = people.mergeDuplicateRoles()

        assertEquals(listOf(mattLanter, tomKane, ashleyEckstein), merged.map { it.id })
        assertEquals(
            listOf("Anakin Skywalker / Clone Trooper", "Yoda / Narrator", "Ahsoka Tano"),
            merged.map { it.role },
        )
    }
}
