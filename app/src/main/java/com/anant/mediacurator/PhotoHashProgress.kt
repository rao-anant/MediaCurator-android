package com.anant.mediacurator

data class PhotoHashProgress(val hashed: Int, val total: Int, val isDone: Boolean) {
    val isActive: Boolean get() = !isDone && total > 0
}
