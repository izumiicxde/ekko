#!/bin/bash

# Run this from the root of your Android project
# Usage: bash setup_ezzza.sh

BASE="app/src/main/java/com/semantic/ekko"

echo "Creating Ekko project structure..."

# --- Create directories ---
mkdir -p "$BASE/data/db"
mkdir -p "$BASE/data/model"
mkdir -p "$BASE/data/repository"
mkdir -p "$BASE/ml"
mkdir -p "$BASE/processing/extractor"
mkdir -p "$BASE/ui/home"
mkdir -p "$BASE/ui/detail"
mkdir -p "$BASE/ui/search"
mkdir -p "$BASE/ui/statistics"
mkdir -p "$BASE/ui/settings"
mkdir -p "$BASE/ui/onboarding"
mkdir -p "$BASE/util"

# --- Helper function to create a Java file with package declaration ---
make_java() {
    local filepath="$1"
    local classname="$2"
    local package="$3"
    cat > "$filepath" <<EOF
package $package;

public class $classname {
    // TODO: implement
}
EOF
}

# --- data/db ---
make_java "$BASE/data/db/AppDatabase.java"       "AppDatabase"       "com.semantic.ezzza.data.db"
make_java "$BASE/data/db/DocumentDao.java"        "DocumentDao"       "com.semantic.ezzza.data.db"
make_java "$BASE/data/db/FolderDao.java"          "FolderDao"         "com.semantic.ezzza.data.db"

# --- data/model ---
make_java "$BASE/data/model/DocumentEntity.java"  "DocumentEntity"    "com.semantic.ezzza.data.model"
make_java "$BASE/data/model/FolderEntity.java"    "FolderEntity"      "com.semantic.ezzza.data.model"
make_java "$BASE/data/model/SearchResult.java"    "SearchResult"      "com.semantic.ezzza.data.model"

# --- data/repository ---
make_java "$BASE/data/repository/DocumentRepository.java" "DocumentRepository" "com.semantic.ezzza.data.repository"
make_java "$BASE/data/repository/FolderRepository.java"   "FolderRepository"   "com.semantic.ezzza.data.repository"

# --- ml ---
make_java "$BASE/ml/EmbeddingEngine.java"         "EmbeddingEngine"   "com.semantic.ezzza.ml"
make_java "$BASE/ml/DocumentClassifier.java"      "DocumentClassifier" "com.semantic.ezzza.ml"
make_java "$BASE/ml/EntityExtractor.java"         "EntityExtractor"   "com.semantic.ezzza.ml"
make_java "$BASE/ml/TextSummarizer.java"          "TextSummarizer"    "com.semantic.ezzza.ml"

# --- processing ---
make_java "$BASE/processing/DocumentIndexer.java" "DocumentIndexer"   "com.semantic.ezzza.processing"
make_java "$BASE/processing/DocumentScanner.java" "DocumentScanner"   "com.semantic.ezzza.processing"
make_java "$BASE/processing/TextPreprocessor.java" "TextPreprocessor" "com.semantic.ezzza.processing"

# --- processing/extractor ---
make_java "$BASE/processing/extractor/PdfTextExtractor.java"  "PdfTextExtractor"  "com.semantic.ezzza.processing.extractor"
make_java "$BASE/processing/extractor/DocxTextExtractor.java" "DocxTextExtractor" "com.semantic.ezzza.processing.extractor"
make_java "$BASE/processing/extractor/PptxTextExtractor.java" "PptxTextExtractor" "com.semantic.ezzza.processing.extractor"
make_java "$BASE/processing/extractor/TxtTextExtractor.java"  "TxtTextExtractor"  "com.semantic.ezzza.processing.extractor"

# --- ui/home ---
make_java "$BASE/ui/home/HomeActivity.java"       "HomeActivity"      "com.semantic.ezzza.ui.home"
make_java "$BASE/ui/home/HomeViewModel.java"      "HomeViewModel"     "com.semantic.ezzza.ui.home"
make_java "$BASE/ui/home/DocumentAdapter.java"    "DocumentAdapter"   "com.semantic.ezzza.ui.home"

# --- ui/detail ---
make_java "$BASE/ui/detail/DetailActivity.java"   "DetailActivity"    "com.semantic.ezzza.ui.detail"
make_java "$BASE/ui/detail/DetailViewModel.java"  "DetailViewModel"   "com.semantic.ezzza.ui.detail"

# --- ui/search ---
make_java "$BASE/ui/search/SearchActivity.java"         "SearchActivity"      "com.semantic.ezzza.ui.search"
make_java "$BASE/ui/search/SearchViewModel.java"        "SearchViewModel"     "com.semantic.ezzza.ui.search"
make_java "$BASE/ui/search/SearchResultAdapter.java"    "SearchResultAdapter" "com.semantic.ezzza.ui.search"

# --- ui/statistics ---
make_java "$BASE/ui/statistics/StatisticsActivity.java" "StatisticsActivity"  "com.semantic.ezzza.ui.statistics"
make_java "$BASE/ui/statistics/StatisticsViewModel.java" "StatisticsViewModel" "com.semantic.ezzza.ui.statistics"

# --- ui/settings ---
make_java "$BASE/ui/settings/SettingsActivity.java"     "SettingsActivity"    "com.semantic.ezzza.ui.settings"
make_java "$BASE/ui/settings/FolderAdapter.java"        "FolderAdapter"       "com.semantic.ezzza.ui.settings"

# --- ui/onboarding ---
make_java "$BASE/ui/onboarding/OnboardingActivity.java" "OnboardingActivity"  "com.semantic.ezzza.ui.onboarding"

# --- util ---
make_java "$BASE/util/PrefsManager.java"          "PrefsManager"      "com.semantic.ezzza.util"
make_java "$BASE/util/FileUtils.java"             "FileUtils"         "com.semantic.ezzza.util"

echo ""
echo "Done. Structure created at: $BASE"
echo "Open the project in Android Studio and sync Gradle before editing files."
