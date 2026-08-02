package com.anodyne.desktop

class MacMenuItem(
    val title: String = "",
    val isSeparator: Boolean = false,
    val action: (() -> Unit)? = null
)
