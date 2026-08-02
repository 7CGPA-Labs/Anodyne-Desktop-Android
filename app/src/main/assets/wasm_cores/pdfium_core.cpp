#include <iostream>
#include <vector>
#include <string>

#ifdef __EMSCRIPTEN__
#include <emscripten/bind.h>
#endif

// Mock of PDFium / MuPDF renderer bindings in C++ for compilation to WASM
class PDFiumCore {
private:
    std::string documentName;
    int pageCount;

public:
    PDFiumCore() : documentName("untitled.pdf"), pageCount(1) {}

    void loadDocument(const std::string& name, int pages) {
        documentName = name;
        pageCount = pages;
        std::cout << "Document " << name << " loaded with " << pages << " pages." << std::endl;
    }

    std::string renderPageToRgba(int pageIndex, int width, int height) {
        // Renders dummy gradient pixel data to represent an active page viewport
        int size = width * height * 4;
        std::string mockBuffer(size, (char)0);
        for (int i = 0; i < size; i += 4) {
            mockBuffer[i] = (char)(18 + (i % 255));     // R
            mockBuffer[i+1] = (char)(18 + (i % 255));   // G
            mockBuffer[i+2] = (char)(26 + (i % 255));   // B
            mockBuffer[i+3] = (char)255;                // Alpha
        }
        return mockBuffer;
    }

    int getPageCount() const { return pageCount; }
    std::string getDocumentName() const { return documentName; }
};

#ifdef __EMSCRIPTEN__
EMSCRIPTEN_BINDINGS(pdfium_core) {
    emscripten::class_<PDFiumCore>("PDFiumCore")
        .constructor()
        .function("loadDocument", &PDFiumCore::loadDocument)
        .function("renderPageToRgba", &PDFiumCore::renderPageToRgba)
        .function("getPageCount", &PDFiumCore::getPageCount)
        .function("getDocumentName", &PDFiumCore::getDocumentName);
}
#else
int main() {
    PDFiumCore pdf;
    pdf.loadDocument("report.pdf", 5);
    std::cout << "PDF Page Count: " << pdf.getPageCount() << std::endl;
    return 0;
}
#endif
