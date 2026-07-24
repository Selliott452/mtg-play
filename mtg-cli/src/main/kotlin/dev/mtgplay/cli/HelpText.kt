package dev.mtgplay.cli

/** The `?`-command help (P6.4 deliverable 3): the input syntax and the driver's shortcuts. */
val HELP_LINES: List<String> =
    listOf(
        "Input help:",
        "  <n>            choose option n (menus are numbered from 1)",
        "  <a>,<b>,<c>    choose several / give an order (comma-separated numbers)",
        "  [Enter]        take the safe default - pass a priority window, keep a hand,",
        "                 declare no attackers/blockers, decline a 'you may', discard the lowest",
        "  y / n          answer a yes/no ('you may') choice",
        "  ?              show this help",
        "  v              redraw the board and the current menu",
        "",
        "The driver auto-passes priority windows whose only option is Pass, and auto-pays a cost",
        "when only one payment plan exists. It stops for you whenever you have a real choice, when",
        "the stack is not empty, and at every attack/block declaration.",
    )
