#pragma once

#include <mpv/client.h>
#include <string>

// Configure before mpv_initialize/loadfile. List-action suffixes such as
// audio-files-append are CLI/config syntax, not libmpv option names.
inline int setLinuxSeparateAudio(mpv_handle *mpv, const std::string &audioUrl) {
    if (audioUrl.empty()) return 0;
    mpv_node entry{};
    entry.format = MPV_FORMAT_STRING;
    entry.u.string = const_cast<char *>(audioUrl.c_str());
    mpv_node_list list{};
    list.num = 1;
    list.values = &entry;
    mpv_node node{};
    node.format = MPV_FORMAT_NODE_ARRAY;
    node.u.list = &list;
    // A native array keeps URL delimiters intact; libmpv copies the value.
    return mpv_set_option(mpv, "audio-files", MPV_FORMAT_NODE, &node);
}
