package com.lin.hippyagent.core.tools.android

import android.content.Context
import android.provider.ContactsContract
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult

class ContactListTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "contact_list",
        description = "获取联系人列表",
        parameters = emptyMap(),
        requiredPermissions = listOf("READ_CONTACTS"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            null, null, null, "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
        )
        val contacts = buildString {
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                    val id = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val phoneCursor = context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(id), null
                    )
                    phoneCursor?.use { pc ->
                        if (pc.moveToNext()) {
                            val phone = pc.getString(pc.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                            appendLine("$name: $phone")
                        }
                    }
                }
            }
        }
        return ToolResult(callId, true, contacts.trimEnd().ifEmpty { "No contacts found" })
    }
}

class ContactSearchTool(
    private val context: Context
) : Tool() {
    override val definition = ToolDefinition(
        name = "contact_search",
        description = "搜索联系人",
        parameters = mapOf(
            "query" to ToolParameter(name = "query", type = "string", description = "搜索关键词", required = true)
        ),
        requiredPermissions = listOf("READ_CONTACTS"),
        isAndroidSpecific = true
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val query = getRequiredArgument(arguments, "query")
        val callId = arguments["callId"] as? String ?: ""
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            null
        )
        val contacts = buildString {
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                    appendLine(name)
                }
            }
        }
        return ToolResult(callId, true, contacts.trimEnd().ifEmpty { "No contacts matching: $query" })
    }
}

