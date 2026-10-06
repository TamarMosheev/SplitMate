package com.example.myapplication.utils

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Backend timestamps come as "...Z" or "...+00:00"; OffsetDateTime parses both. Null/invalid -> null. */
fun parseTimestamp(value: String?): OffsetDateTime? =
    value?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

private val displayFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

private val dateOnlyFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/** dd/MM/yyyy in the device zone, or null if the timestamp is missing/unparseable. */
fun formatDateOnly(value: String?): String? =
    parseTimestamp(value)?.atZoneSameInstant(ZoneId.systemDefault())?.format(dateOnlyFormat)

/** Device-zone display text, or null if the timestamp is missing/unparseable. */
fun formatTimestamp(value: String?): String? =
    parseTimestamp(value)?.atZoneSameInstant(ZoneId.systemDefault())?.format(displayFormat)
