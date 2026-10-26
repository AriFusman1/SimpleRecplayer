import { ReactNode } from 'react';
import { List, Text } from '@mantine/core';

/** A small label above a value, used in the record cards and the upload panels. */
export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <Text size="xs" c="dimmed">{label}</Text>
      <Text size="sm" component="div">{children}</Text>
    </div>
  );
}

/** The parser's warnings (fragments, truncated packets), shown in yellow. */
export function Warnings({ warnings }: { warnings: string[] }) {
  if (warnings.length === 0) return null;
  return (
    <List size="sm" c="yellow" mt="sm">
      {warnings.map((w) => <List.Item key={w}>{w}</List.Item>)}
    </List>
  );
}
