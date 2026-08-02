#include <iostream>
#include <vector>
#include <string>

#ifdef __EMSCRIPTEN__
#include <emscripten/bind.h>
#endif

// Mock of Archive/Compression pipeline in C++ for compilation to WASM
class LibarchiveCore {
public:
    LibarchiveCore() {}

    std::string extractArchive(const std::string& archiveData) {
        // Return dummy listing of extracted archives
        std::cout << "Extracting archive of size: " << archiveData.size() << " bytes" << std::endl;
        return "[\"document.pdf\", \"image.png\", \"notes.txt\"]";
    }

    std::string compressFiles(const std::vector<std::string>& fileNames) {
        std::cout << "Compressing " << fileNames.size() << " files into archive." << std::endl;
        return "MOCK_ZIP_ARCHIVE_DATA_STREAM";
    }
};

#ifdef __EMSCRIPTEN__
EMSCRIPTEN_BINDINGS(libarchive_core) {
    emscripten::class_<LibarchiveCore>("LibarchiveCore")
        .constructor()
        .function("extractArchive", &LibarchiveCore::extractArchive)
        .function("compressFiles", &LibarchiveCore::compressFiles);
}
#else
int main() {
    LibarchiveCore archiver;
    std::cout << "Extracted files: " << archiver.extractArchive("MOCK_DATA") << std::endl;
    return 0;
}
#endif
