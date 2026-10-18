package io.github.JaJaJim.lightonkaroo.protocol

/**
 * A [CustomModeConfig] as one line of text, to keep a snapshot in SharedPreferences:
 * `mode|selected|subtype/lightNum=pct,…/cycle/ratio;…` (empty cycle/ratio = absent).
 */
object CustomModeText {
    fun encode(c: CustomModeConfig): String =
        "${c.mode}|${c.selected}|" + c.patterns.joinToString(";") { p ->
            "${p.subtype}/" + p.lights.joinToString(",") { "${it.lightNum}=${it.pct}" } +
                "/${p.cycleSeconds ?: ""}/${p.ratioPercent ?: ""}"
        }

    /** Null for anything that isn't exactly what [encode] writes. */
    fun decode(s: String?): CustomModeConfig? = runCatching {
        val (mode, selected, patterns) = s!!.split("|").also { require(it.size == 3) }
        CustomModeConfig(
            mode.toInt(),
            selected.toInt(),
            patterns.split(";").filter { it.isNotEmpty() }.map { p ->
                val parts = p.split("/")
                require(parts.size == 4)
                CustomPattern(
                    subtype = parts[0].toInt(),
                    lights = parts[1].split(",").filter { it.isNotEmpty() }.map { l ->
                        val (n, pct) = l.split("=").also { require(it.size == 2) }
                        CustomLight(n.toInt(), pct.toInt())
                    },
                    cycleSeconds = parts[2].takeIf { it.isNotEmpty() }?.toInt(),
                    ratioPercent = parts[3].takeIf { it.isNotEmpty() }?.toInt(),
                )
            },
        )
    }.getOrNull()
}


