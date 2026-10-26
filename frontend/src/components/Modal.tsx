import { ReactNode } from 'react';

interface ModalProps {
  title: string;
  children: ReactNode;
  /** Buttons shown at the bottom right. */
  footer: ReactNode;
  onClose: () => void;
}

/** A simple centered dialog over a dark backdrop. Clicking the backdrop closes it. */
export function Modal({ title, children, footer, onClose }: ModalProps) {
  return (
    <div className="backdrop" onMouseDown={onClose}>
      <div className="modal" onMouseDown={(e) => e.stopPropagation()}>
        <div className="modal-title">{title}</div>
        <div className="modal-body">{children}</div>
        <div className="modal-footer">{footer}</div>
      </div>
    </div>
  );
}

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
    <Modal
      title={title}
      onClose={onCancel}
      footer={
        <>
          <button onClick={onCancel}>Cancel</button>
          <button className="danger" onClick={onConfirm}>{confirmLabel}</button>
        </>
      }
    >
      {message}
    </Modal>
  );
}
