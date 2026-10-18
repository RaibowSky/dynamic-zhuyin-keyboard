package com.ioszhuyin.keyboard

/** Space-delimited entries preserve variation selectors and joined emoji as one key. */
internal object EmojiCatalog {
    const val PAGE_SIZE = 29 // Three rows of ten, with a permanent backspace key.

    data class Category(val label: String, val entries: List<String>) {
        val pageCount: Int get() = (entries.size + PAGE_SIZE - 1) / PAGE_SIZE
        fun page(index: Int): List<String> = entries.drop(index * PAGE_SIZE).take(PAGE_SIZE)
    }

    private fun category(label: String, entries: String) = Category(label, entries.split(" "))

    val categories = listOf(
        category("表情", "😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🤩 🥳 😏 😒 😞 😔 😟 😕 🙁 ☹️ 😣 😖 😫 😩 😢 😭 😤 😠 😡 🤬 😳 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 🤥 😶 😐 😑 😬 🙄 😯 😮 😲 😴 🤤 😪 😵 🤐 🤒 🤕 🤢 🤮 🤧 😷 😈 👿 👻 💀 👽 🤖 💩"),
        category("手勢", "👋 🤚 🖐️ ✋ 🖖 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 💪 ✍️ 💅 🤳 👂 👃 👀 👁️ 👅 👄 👶 👦 👧 👨 👩 👴 👵 👨‍👩‍👧‍👦"),
        category("動物", "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🕷️ 🐢 🐍 🦎 🐙 🦑 🦀 🐠 🐟 🐬 🐳 🦈 🌹 🌸 🌻 🌷 🌱 🌲 🌴 🌵 🍀 🍁 🍄"),
        category("飲食", "🍎 🍏 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍈 🍒 🍑 🍍 🥝 🥑 🍅 🍆 🥒 🥕 🌽 🌶️ 🥔 🍠 🍞 🥐 🥖 🧀 🥚 🍳 🥓 🥞 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜 🍲 🍛 🍣 🍱 🍙 🍚 🍘 🥟 🍤 🍥 🍡 🍦 🍧 🍨 🎂 🍰 🍩 🍪 🍫 🍬 🍭 🍮 🍯 ☕ 🍵 🍼 🥛 🍺 🍻 🥂 🍷 🍹 🍸"),
        category("活動", "🎉 🎊 🎁 🎈 🎀 🎂 🎄 🎃 🎆 🎇 ✨ 🎏 🎐 🎑 🎎 🎍 🎋 🎓 🏆 🏅 🥇 🥈 🥉 ⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🏒 ⛳ 🎣 🎽 🎿 🏂 🏄 🏊 🚴 🏃 🎮 🎲 🎯 🎳 🎵 🎶 🎤 🎧 🎸 🎹 🎺 🎻 🥁 🎨 🎬 🎭"),
        category("旅行", "🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛵 🏍️ 🚲 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🚁 🚀 🛸 🚢 ⛵ 🚤 ⚓ ⛽ 🚦 🗺️ 🗼 🗽 🏰 🏯 🎡 🎢 ⛲ ⛱️ 🏖️ 🏝️ 🏔️ ⛰️ 🌋 🏕️ 🏠 🏢 🏫 🏥 🏨 ⛪ ⛩️ 🌅 🌄 🌠 🌈 ☀️ 🌤️ ☁️ 🌧️ ⛈️ ❄️ ⛄ 🌙 ⭐ 🌟"),
        category("符號", "❤️ 🧡 💛 💚 💙 💜 🖤 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ☯️ ✝️ ☪️ 🕉️ 🔥 💧 💨 💫 💥 💦 💤 ✅ ☑️ ✔️ ❌ ❎ ⭕ ❗ ❓ ❕ ❔ ⚠️ 🚫 ⛔ 💯 🔴 🔵 ⚪ ⚫ 🔶 🔷 🔺 🔻 ➕ ➖ ➗ ✖️ ♻️ 🔒 🔓 🔑 💡 📱 💻 📷 📚 ✏️ 📝 📌 📎 ✉️ 📦 💰 💵 🛒" )
    )
}
