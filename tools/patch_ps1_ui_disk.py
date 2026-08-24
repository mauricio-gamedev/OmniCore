#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/com/omnicore/emulator/emulation/GamepadOverlayView.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    '        val labels = arrayOf("SALVAR", "CARREGAR", "STATUS", "EDITAR", "VISUAL", "LAYOUT", "CHEATS", "SAIR")\n',
    '        val labels = arrayOf("SALVAR", "CARREGAR", "STATUS", "EDITAR", "VISUAL", "LAYOUT", "CHEATS", "DISCO", "SAIR")\n',
    "quick-menu labels",
)

replace_once(
    '        val panelH = base * 0.39f\n',
    '        val panelH = base * 0.47f\n',
    "quick-menu panel height",
)

replace_once(
    '        val contentH = panel.height() - footer - gap * 5\n        val itemW = (panel.width() - gap * 3) / 2f\n        val itemH = contentH / 4f\n',
    '        val contentH = panel.height() - footer - gap * 6\n        val itemW = (panel.width() - gap * 3) / 2f\n        val itemH = contentH / 5f\n',
    "quick-menu five-row geometry",
)

replace_once(
    '        val item = (0..7).firstOrNull { quickMenuItemRect(it).contains(x, y) }\n',
    '        val item = (0..8).firstOrNull { quickMenuItemRect(it).contains(x, y) }\n',
    "quick-menu hit range",
)

replace_once(
    '''            6 -> showCheatDialog()\n            7 -> activity?.finish()\n''',
    '''            6 -> showCheatDialog()\n            7 -> showDiskDialog()\n            8 -> activity?.finish()\n''',
    "quick-menu disk action",
)

replace_once(
    '''    private fun showStatusDialog() {\n        val enabled = CheatStore.load(context, gameKey).count { it.enabled }\n        AlertDialog.Builder(context)\n            .setTitle(gameTitle)\n            .setMessage("${NativeBridge.lastMessage()}\\n\\nPreset: ${config.overlayPreset.label}\\nControle: ${config.analogMode.label}\\nCheats ativos: $enabled")\n''',
    '''    private fun showStatusDialog() {\n        val enabled = CheatStore.load(context, gameKey).count { it.enabled }\n        val disk = NativeBridge.diskState()\n        val diskLabel = when {\n            disk.count > 1 -> "${disk.index + 1}/${disk.count}${if (disk.ejected) " • tampa aberta" else ""}"\n            disk.count == 1 -> "1/1"\n            else -> "indisponível"\n        }\n        AlertDialog.Builder(context)\n            .setTitle(gameTitle)\n            .setMessage("${NativeBridge.lastMessage()}\\n\\nPreset: ${config.overlayPreset.label}\\nControle: ${config.analogMode.label}\\nDisco: $diskLabel\\nCheats ativos: $enabled")\n''',
    "status disk state",
)

anchor = '''    private fun showCheatDialog() {\n'''
insert = '''    private fun showDiskDialog() {\n        val state = NativeBridge.diskState()\n        if (state.count <= 0) {\n            showToast("O core não expôs controle de disco para esta sessão")\n            return\n        }\n        if (state.count == 1) {\n            AlertDialog.Builder(context)\n                .setTitle("Disco • $gameTitle")\n                .setMessage("Esta sessão possui apenas um disco. Em jogos multi-disc, o OmniCore mostrará todos os discos aqui sem reiniciar o jogo.")\n                .setPositiveButton("OK", null)\n                .show()\n            return\n        }\n\n        val labels = Array(state.count) { index ->\n            buildString {\n                append("Disco ").append(index + 1)\n                if (index == state.index) append(" • inserido")\n            }\n        }\n        AlertDialog.Builder(context)\n            .setTitle("Trocar disco • $gameTitle")\n            .setSingleChoiceItems(labels, state.index) { dialog, which ->\n                if (which == state.index) {\n                    dialog.dismiss()\n                    showToast("O disco ${which + 1} já está inserido")\n                    return@setSingleChoiceItems\n                }\n                if (NativeBridge.setDiskIndex(which)) {\n                    dialog.dismiss()\n                    showToast("Trocando para o disco ${which + 1}…")\n                    postDelayed({\n                        val updated = NativeBridge.diskState()\n                        if (updated.index == which) {\n                            showToast("Disco ${which + 1}/${updated.count} inserido")\n                        } else {\n                            showToast(NativeBridge.lastMessage())\n                        }\n                    }, 450L)\n                } else {\n                    showToast("A troca de disco não está disponível agora")\n                }\n            }\n            .setNegativeButton("Cancelar", null)\n            .show()\n    }\n\n'''
replace_once(anchor, insert + anchor, "disk dialog insertion")

path.write_text(text, encoding="utf-8")
print("PS1 Quick Menu disk-control UI patched successfully")
