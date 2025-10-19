package dev.jigen.providers.innertube.utils

import dev.jigen.providers.innertube.Innertube
import dev.jigen.providers.innertube.models.PlaylistPanelVideoRenderer
import dev.jigen.providers.innertube.models.isExplicit

fun Innertube.SongItem.Companion.from(renderer: PlaylistPanelVideoRenderer) = Innertube.SongItem(
    info = Innertube.Info(
        name = renderer
            .title
            ?.text,
        endpoint = renderer
            .navigationEndpoint
            ?.watchEndpoint
    ),
    authors = renderer
        .longBylineText
        ?.splitBySeparator()
        ?.getOrNull(0)
        ?.map(Innertube::Info),
    album = renderer
        .longBylineText
        ?.splitBySeparator()
        ?.getOrNull(1)
        ?.getOrNull(0)
        ?.let(Innertube::Info),
    thumbnail = renderer
        .thumbnail
        ?.thumbnails
        ?.getOrNull(0),
    durationText = renderer
        .lengthText
        ?.text,
    explicit = renderer.badges.isExplicit
).takeIf { it.info?.endpoint?.videoId != null }
