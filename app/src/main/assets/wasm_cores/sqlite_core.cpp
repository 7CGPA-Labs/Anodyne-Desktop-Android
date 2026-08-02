#include <iostream>
#include <string>
#include <vector>
#include <sstream>

#ifdef __EMSCRIPTEN__
#include <emscripten/bind.h>
#endif

// Simple Database Table representation for local compilation to WASM
struct DBRecord {
    int id;
    std::string key;
    std::string value;
};

class SQLiteCore {
private:
    std::vector<DBRecord> records;
    int nextId;

public:
    SQLiteCore() : nextId(1) {
        // Pre-populate with default config records
        insertRecord("theme", "glassmorphic");
        insertRecord("clock_format", "24h");
        insertRecord("locale", "en-US");
    }

    int insertRecord(const std::string& key, const std::string& value) {
        DBRecord rec = { nextId++, key, value };
        records.push_back(rec);
        return rec.id;
    }

    std::string queryRecord(const std::string& key) {
        for (const auto& rec : records) {
            if (rec.key == key) {
                return rec.value;
            }
        }
        return "";
    }

    std::string getAllRecordsJson() {
        std::stringstream ss;
        ss << "[";
        for (size_t i = 0; i < records.size(); ++i) {
            ss << "{\"id\":" << records[i].id 
               << ",\"key\":\"" << records[i].key 
               << "\",\"value\":\"" << records[i].value << "\"}";
            if (i < records.size() - 1) {
                ss << ",";
            }
        }
        ss << "]";
        return ss.str();
    }
};

#ifdef __EMSCRIPTEN__
EMSCRIPTEN_BINDINGS(sqlite_core) {
    emscripten::class_<SQLiteCore>("SQLiteCore")
        .constructor()
        .function("insertRecord", &SQLiteCore::insertRecord)
        .function("queryRecord", &SQLiteCore::queryRecord)
        .function("getAllRecordsJson", &SQLiteCore::getAllRecordsJson);
}
#else
int main() {
    SQLiteCore db;
    std::cout << "Local SQLite mock records JSON: " << db.getAllRecordsJson() << std::endl;
    return 0;
}
#endif
