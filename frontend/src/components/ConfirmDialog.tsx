import { ReactNode } from 'react';
import { Button, Group, Modal } from '@mantine/core';

interface ConfirmProps {
  title: string;
  message: ReactNode;
  confirmLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
}

/** "Are you sure?" dialog with a red confirm button. */
export function ConfirmDialog({ title, message, confirmLabel, onConfirm, onCancel }: ConfirmProps) {
  return (
    <Modal opened onClose={onCancel} title={title} centered>
      {message}
      <Group justify="flex-end" mt="lg">
        <Button variant="default" onClick={onCancel}>Cancel</Button>
        <Button color="red" onClick={onConfirm}>{confirmLabel}</Button>
      </Group>
    </Modal>
  );
}
