#!/bin/bash
# Run from: /home/izumii/coding/java-applications/ekko-semantic/ekko-app

BASE="app/src/main/java/com/semantic/ekko"
RES="app/src/main/res"

# New directories
mkdir -p "$BASE/ui/main"
mkdir -p "$BASE/ui/ask"
mkdir -p "$RES/menu"

# New Java files
touch "$BASE/ui/main/MainActivity.java"
touch "$BASE/ui/ask/AskFragment.java"

# New fragment files (layouts)
touch "$RES/layout/fragment_home.xml"
touch "$RES/layout/fragment_search.xml"
touch "$RES/layout/fragment_ask.xml"
touch "$RES/layout/fragment_settings.xml"
touch "$RES/layout/activity_main.xml"

# New drawable
touch "$RES/drawable/ic_chat.xml"

# New menu
touch "$RES/menu/bottom_nav_menu.xml"

echo "All new files created."
echo "Now copy contents from outputs into each file."
echo "Also update existing files:"
echo "  - ui/home/HomeFragment.java (new file, replaces HomeActivity role)"
echo "  - ui/search/SearchFragment.java (new file)"
echo "  - ui/settings/SettingsFragment.java (new file)"
