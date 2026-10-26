package com.pcapplayer.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pcapplayer.Json;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps all categories, tags and records in memory, and writes them to S3 on every change.
 *
 * S3 layout:
 *   categories.json        list of Category
 *   tags.json              list of Tag
 *   records/{id}.json      one PcapRecord
 *   pcaps/{id}.pcap        the record's pcap file
 *
 * Reads come from the ConcurrentHashMaps (fast, thread-safe). Methods that write call S3 and
 * BLOCK, so the API calls them inside executeBlocking.
 */
public final class MetadataStore {

    private static final String CATEGORIES_KEY = "categories.json";
    private static final String TAGS_KEY = "tags.json";

    private final S3Storage s3;
    private final ConcurrentHashMap<String, Category> categories = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Tag> tags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PcapRecord> records = new ConcurrentHashMap<>();

    public MetadataStore(S3Storage s3) {
        this.s3 = s3;
    }

    /** Called once at startup: loads everything from S3 into memory. */
    public void load() throws IOException {
        s3.ensureBucket();

        byte[] categoriesJson = s3.getBytes(CATEGORIES_KEY);
        if (categoriesJson != null) {
            for (Category c : Json.MAPPER.readValue(categoriesJson, new TypeReference<List<Category>>() { })) {
                categories.put(c.id(), c);
            }
        }
        byte[] tagsJson = s3.getBytes(TAGS_KEY);
        if (tagsJson != null) {
            for (Tag t : Json.MAPPER.readValue(tagsJson, new TypeReference<List<Tag>>() { })) {
                tags.put(t.id(), t);
            }
        }
        for (String key : s3.listKeys("records/")) {
            byte[] json = s3.getBytes(key);
            if (json != null) {
                PcapRecord r = Json.MAPPER.readValue(json, PcapRecord.class);
                records.put(r.id(), r);
            }
        }
    }

    // ------------------------------------------------------------------ categories

    public List<Category> categories() {
        List<Category> list = new ArrayList<>(categories.values());
        list.sort(Comparator.comparing(Category::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public Category category(String id) {
        Category c = id == null ? null : categories.get(id);
        if (c == null) throw new NoSuchElementException("Category not found");
        return c;
    }

    /** Adds or replaces a category. */
    public void saveCategory(Category category) {
        categories.put(category.id(), category);
        writeCategories();
    }

    /** Deletes a category together with its records (and their files) and its tags. */
    public void deleteCategory(String id) {
        category(id); // throws if missing
        for (PcapRecord r : records.values()) {
            if (id.equals(r.categoryId())) {
                deleteRecord(r.id());
            }
        }
        boolean tagsRemoved = tags.values().removeIf(t -> id.equals(t.categoryId()));
        if (tagsRemoved) writeTags();
        categories.remove(id);
        writeCategories();
    }

    // ------------------------------------------------------------------ tags

    public List<Tag> tags() {
        List<Tag> list = new ArrayList<>(tags.values());
        list.sort(Comparator.comparing(Tag::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public Tag tag(String id) {
        Tag t = id == null ? null : tags.get(id);
        if (t == null) throw new NoSuchElementException("Tag not found");
        return t;
    }

    public void saveTag(Tag tag) {
        tags.put(tag.id(), tag);
        writeTags();
    }

    /** Deletes a tag and removes it from every record that uses it. */
    public void deleteTag(String id) {
        tag(id);
        for (PcapRecord r : records.values()) {
            if (r.tagIds().contains(id)) {
                List<String> tagIds = new ArrayList<>(r.tagIds());
                tagIds.remove(id);
                saveRecord(new PcapRecord(r.id(), r.name(), r.description(), r.categoryId(), tagIds,
                        r.ports(), r.ipFilter(), r.startTime(), r.endTime(), r.recordingStart(),
                        r.recordingEnd(), r.stats(), r.fileName(), r.fileSize(), r.uploadDate()));
            }
        }
        tags.remove(id);
        writeTags();
    }

    // ------------------------------------------------------------------ records

    /** All records, newest upload first. */
    public List<PcapRecord> records() {
        List<PcapRecord> list = new ArrayList<>(records.values());
        list.sort(Comparator.comparingLong(PcapRecord::uploadDate).reversed());
        return list;
    }

    public PcapRecord record(String id) {
        PcapRecord r = id == null ? null : records.get(id);
        if (r == null) throw new NoSuchElementException("Record not found");
        return r;
    }

    /** Adds or replaces a record's metadata. */
    public void saveRecord(PcapRecord record) {
        s3.putBytes(recordKey(record.id()), Json.write(record).getBytes());
        records.put(record.id(), record);
    }

    public void deleteRecord(String id) {
        s3.delete(pcapKey(id));
        s3.delete(recordKey(id));
        records.remove(id);
    }

    public void savePcapFile(String recordId, Path file) {
        s3.putFile(pcapKey(recordId), file);
    }

    public void downloadPcapFile(String recordId, Path target) throws IOException {
        s3.downloadToFile(pcapKey(recordId), target);
    }

    // ------------------------------------------------------------------ helpers

    private void writeCategories() {
        s3.putBytes(CATEGORIES_KEY, Json.write(categories()).getBytes());
    }

    private void writeTags() {
        s3.putBytes(TAGS_KEY, Json.write(tags()).getBytes());
    }

    private static String recordKey(String id) {
        return "records/" + id + ".json";
    }

    private static String pcapKey(String id) {
        return "pcaps/" + id + ".pcap";
    }
}
