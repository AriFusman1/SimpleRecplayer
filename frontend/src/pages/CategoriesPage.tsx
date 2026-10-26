import { useState } from 'react';
import { Button, Card, Group, Select, SimpleGrid, Table, Text, TextInput } from '@mantine/core';
import { api, Category, Mode, MODE_HINTS, MODE_LABELS, Tag } from '../api';
import { AppData } from '../App';
import { ConfirmDialog } from '../components/ConfirmDialog';

const MODE_OPTIONS = (Object.keys(MODE_LABELS) as Mode[]).map((m) => ({ value: m, label: MODE_LABELS[m] }));
const GLOBAL = 'global'; // Select value for "no category" (Mantine options can't use an empty value)

/** Create, rename and delete categories and tags. */
export function CategoriesPage({ data }: { data: AppData }) {
  const { categories, tags, records, reload, showError } = data;

  const [newCategoryName, setNewCategoryName] = useState('');
  const [newCategoryMode, setNewCategoryMode] = useState<Mode>('TCP_SERVER');
  const [newTagName, setNewTagName] = useState('');
  const [newTagScope, setNewTagScope] = useState(GLOBAL); // GLOBAL or a category id

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
    if (await run(() => api.createTag(newTagName, newTagScope === GLOBAL ? null : newTagScope))) setNewTagName('');
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
    if (renamingId !== id) return <Table.Td>{name}</Table.Td>;
    return (
      <Table.Td>
        <Group gap={4} wrap="nowrap">
          <TextInput size="xs" value={renameText} autoFocus onChange={(e) => setRenameText(e.currentTarget.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') saveRename(kind); if (e.key === 'Escape') setRenamingId(''); }} />
          <Button size="xs" onClick={() => saveRename(kind)}>Save</Button>
          <Button size="xs" variant="default" onClick={() => setRenamingId('')}>Cancel</Button>
        </Group>
      </Table.Td>
    );
  }

  /** Rename / Delete buttons at the end of a row. */
  function rowActions(onRename: () => void, onDelete: () => void) {
    return (
      <Table.Td>
        <Group gap={4} justify="flex-end" wrap="nowrap">
          <Button size="xs" variant="default" onClick={onRename}>Rename</Button>
          <Button size="xs" color="red" variant="light" onClick={onDelete}>Delete</Button>
        </Group>
      </Table.Td>
    );
  }

  return (
    <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
      <Card withBorder>
        <Text fw={600}>Categories</Text>
        <Text size="sm" c="dimmed" mb="sm">The mode describes the original service and cannot be changed later.</Text>
        <Table>
          <Table.Thead>
            <Table.Tr><Table.Th>Name</Table.Th><Table.Th>Mode</Table.Th><Table.Th>Records</Table.Th><Table.Th /></Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {categories.map((c) => (
              <Table.Tr key={c.id}>
                {nameCell(c.id, c.name, 'category')}
                <Table.Td title={MODE_HINTS[c.mode]}>{MODE_LABELS[c.mode]}</Table.Td>
                <Table.Td>{recordCount(c.id)}</Table.Td>
                {rowActions(() => startRename(c.id, c.name), () => setDeletingCategory(c))}
              </Table.Tr>
            ))}
            {categories.length === 0 && (
              <Table.Tr><Table.Td colSpan={4}><Text size="sm" c="dimmed">No categories yet</Text></Table.Td></Table.Tr>
            )}
          </Table.Tbody>
        </Table>
        <Group gap="xs" mt="md" wrap="nowrap">
          <TextInput style={{ flex: 1 }} placeholder="New category name" value={newCategoryName}
            onChange={(e) => setNewCategoryName(e.currentTarget.value)} />
          <Select w={140} allowDeselect={false} data={MODE_OPTIONS} value={newCategoryMode}
            onChange={(value) => setNewCategoryMode(value as Mode)} />
          <Button onClick={addCategory}>Add</Button>
        </Group>
        <Text size="sm" c="dimmed" mt={4}>{MODE_HINTS[newCategoryMode]}.</Text>
      </Card>

      <Card withBorder>
        <Text fw={600}>Tags</Text>
        <Text size="sm" c="dimmed" mb="sm">Global tags can be used on any record; category tags only on that category's records.</Text>
        <Table>
          <Table.Thead>
            <Table.Tr><Table.Th>Name</Table.Th><Table.Th>Scope</Table.Th><Table.Th>Records</Table.Th><Table.Th /></Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {tags.map((t) => (
              <Table.Tr key={t.id}>
                {nameCell(t.id, t.name, 'tag')}
                <Table.Td>{categoryName(t.categoryId)}</Table.Td>
                <Table.Td>{tagUseCount(t.id)}</Table.Td>
                {rowActions(() => startRename(t.id, t.name), () => setDeletingTag(t))}
              </Table.Tr>
            ))}
            {tags.length === 0 && (
              <Table.Tr><Table.Td colSpan={4}><Text size="sm" c="dimmed">No tags yet</Text></Table.Td></Table.Tr>
            )}
          </Table.Tbody>
        </Table>
        <Group gap="xs" mt="md" wrap="nowrap">
          <TextInput style={{ flex: 1 }} placeholder="New tag name" value={newTagName}
            onChange={(e) => setNewTagName(e.currentTarget.value)} />
          <Select w={140} allowDeselect={false} value={newTagScope} onChange={(value) => setNewTagScope(value ?? GLOBAL)}
            data={[{ value: GLOBAL, label: 'Global' }, ...categories.map((c) => ({ value: c.id, label: c.name }))]} />
          <Button onClick={addTag}>Add</Button>
        </Group>
      </Card>

      {deletingCategory && (
        <ConfirmDialog
          title="Delete category"
          message={
            <Text size="sm">
              Delete <b>{deletingCategory.name}</b>? This also deletes its{' '}
              <b>{recordCount(deletingCategory.id)} records</b> with their pcap files, and its{' '}
              {tags.filter((t) => t.categoryId === deletingCategory.id).length} tags. This cannot be undone.
            </Text>
          }
          confirmLabel="Delete"
          onConfirm={async () => { await run(() => api.deleteCategory(deletingCategory.id)); setDeletingCategory(null); }}
          onCancel={() => setDeletingCategory(null)}
        />
      )}
      {deletingTag && (
        <ConfirmDialog
          title="Delete tag"
          message={<Text size="sm">Delete tag <b>{deletingTag.name}</b>? It will be removed from {tagUseCount(deletingTag.id)} records.</Text>}
          confirmLabel="Delete"
          onConfirm={async () => { await run(() => api.deleteTag(deletingTag.id)); setDeletingTag(null); }}
          onCancel={() => setDeletingTag(null)}
        />
      )}
    </SimpleGrid>
  );
}
