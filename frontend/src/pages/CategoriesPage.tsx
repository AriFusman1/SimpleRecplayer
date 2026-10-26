import { useState } from 'react';
import { api, Category, Mode, MODE_HINTS, MODE_LABELS, Tag } from '../api';
import { AppData } from '../App';
import { ConfirmDialog } from '../components/Modal';

/** Create, rename and delete categories and tags. */
export function CategoriesPage({ data }: { data: AppData }) {
  const { categories, tags, records, reload, showError } = data;

  const [newCategoryName, setNewCategoryName] = useState('');
  const [newCategoryMode, setNewCategoryMode] = useState<Mode>('TCP_SERVER');
  const [newTagName, setNewTagName] = useState('');
  const [newTagCategoryId, setNewTagCategoryId] = useState(''); // '' = global

  // Inline rename: the id being renamed and the text in the box.
  const [renamingId, setRenamingId] = useState('');
  const [renameText, setRenameText] = useState('');

  const [deletingCategory, setDeletingCategory] = useState<Category | null>(null);
  const [deletingTag, setDeletingTag] = useState<Tag | null>(null);

  /** Runs an API call, reloads the data, and shows any error. Returns true on success. */
  async function run(action: () => Promise<unknown>): Promise<boolean> {
    try {
      await action();
      await reload();
      return true;
    } catch (e) {
      showError(e);
      return false;
    }
  }

  async function addCategory() {
    if (await run(() => api.createCategory(newCategoryName, newCategoryMode))) setNewCategoryName('');
  }

  async function addTag() {
    if (await run(() => api.createTag(newTagName, newTagCategoryId || null))) setNewTagName('');
  }

  function startRename(id: string, name: string) {
    setRenamingId(id);
    setRenameText(name);
  }

  async function saveRename(kind: 'category' | 'tag') {
    const ok = await run(() => kind === 'category'
      ? api.renameCategory(renamingId, renameText)
      : api.renameTag(renamingId, renameText));
    if (ok) setRenamingId('');
  }

  const recordCount = (categoryId: string) => records.filter((r) => r.categoryId === categoryId).length;
  const tagUseCount = (tagId: string) => records.filter((r) => r.tagIds.includes(tagId)).length;
  const categoryName = (id: string | null) => id ? categories.find((c) => c.id === id)?.name ?? '?' : 'Global';

  /** The name cell: plain text, or a text box with Save/Cancel while renaming. */
  function nameCell(id: string, name: string, kind: 'category' | 'tag') {
    if (renamingId !== id) return <td>{name}</td>;
    return (
      <td>
        <div className="inline-edit">
          <input value={renameText} autoFocus onChange={(e) => setRenameText(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') saveRename(kind); if (e.key === 'Escape') setRenamingId(''); }} />
          <button className="primary" onClick={() => saveRename(kind)}>Save</button>
          <button onClick={() => setRenamingId('')}>Cancel</button>
        </div>
      </td>
    );
  }

  return (
    <div className="two-columns">
      <section className="panel">
        <div className="panel-title">Categories</div>
        <p className="muted small">The mode describes the original service and cannot be changed later.</p>
        <table>
          <thead><tr><th>Name</th><th>Mode</th><th>Records</th><th /></tr></thead>
          <tbody>
            {categories.map((c) => (
              <tr key={c.id}>
                {nameCell(c.id, c.name, 'category')}
                <td title={MODE_HINTS[c.mode]}>{MODE_LABELS[c.mode]}</td>
                <td>{recordCount(c.id)}</td>
                <td className="right">
                  <button onClick={() => startRename(c.id, c.name)}>Rename</button>
                  <button className="danger" onClick={() => setDeletingCategory(c)}>Delete</button>
                </td>
              </tr>
            ))}
            {categories.length === 0 && <tr><td colSpan={4} className="muted">No categories yet</td></tr>}
          </tbody>
        </table>
        <div className="add-row">
          <input placeholder="New category name" value={newCategoryName} onChange={(e) => setNewCategoryName(e.target.value)} />
          <select value={newCategoryMode} onChange={(e) => setNewCategoryMode(e.target.value as Mode)}>
            {(Object.keys(MODE_LABELS) as Mode[]).map((m) => <option key={m} value={m}>{MODE_LABELS[m]}</option>)}
          </select>
          <button className="primary" onClick={addCategory}>Add</button>
        </div>
        <p className="muted small">{MODE_HINTS[newCategoryMode]}.</p>
      </section>

      <section className="panel">
        <div className="panel-title">Tags</div>
        <p className="muted small">Global tags can be used on any record; category tags only on that category's records.</p>
        <table>
          <thead><tr><th>Name</th><th>Scope</th><th>Records</th><th /></tr></thead>
          <tbody>
            {tags.map((t) => (
              <tr key={t.id}>
                {nameCell(t.id, t.name, 'tag')}
                <td>{categoryName(t.categoryId)}</td>
                <td>{tagUseCount(t.id)}</td>
                <td className="right">
                  <button onClick={() => startRename(t.id, t.name)}>Rename</button>
                  <button className="danger" onClick={() => setDeletingTag(t)}>Delete</button>
                </td>
              </tr>
            ))}
            {tags.length === 0 && <tr><td colSpan={4} className="muted">No tags yet</td></tr>}
          </tbody>
        </table>
        <div className="add-row">
          <input placeholder="New tag name" value={newTagName} onChange={(e) => setNewTagName(e.target.value)} />
          <select value={newTagCategoryId} onChange={(e) => setNewTagCategoryId(e.target.value)}>
            <option value="">Global</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          <button className="primary" onClick={addTag}>Add</button>
        </div>
      </section>

      {deletingCategory && (
        <ConfirmDialog
          title="Delete category"
          message={
            <p>
              Delete <b>{deletingCategory.name}</b>? This also deletes its{' '}
              <b>{recordCount(deletingCategory.id)} records</b> with their pcap files, and its{' '}
              {tags.filter((t) => t.categoryId === deletingCategory.id).length} tags. This cannot be undone.
            </p>
          }
          confirmLabel="Delete"
          onConfirm={async () => { await run(() => api.deleteCategory(deletingCategory.id)); setDeletingCategory(null); }}
          onCancel={() => setDeletingCategory(null)}
        />
      )}
      {deletingTag && (
        <ConfirmDialog
          title="Delete tag"
          message={<p>Delete tag <b>{deletingTag.name}</b>? It will be removed from {tagUseCount(deletingTag.id)} records.</p>}
          confirmLabel="Delete"
          onConfirm={async () => { await run(() => api.deleteTag(deletingTag.id)); setDeletingTag(null); }}
          onCancel={() => setDeletingTag(null)}
        />
      )}
    </div>
  );
}
