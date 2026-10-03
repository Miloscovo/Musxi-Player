// Adapted from shadcn/ui d75a96ab781f3d659be1ad287347d5887ce9f2fc.
// Copyright (c) 2023 shadcn. MIT: licenses/frontend/shadcn-ui-LICENSE.txt.
// Used primitives only; individual Radix imports and existing theme CSS.
import type { ComponentProps } from 'react';
import * as Primitive from '@radix-ui/react-dialog';
import { cn } from '@/lib/utils';

const Dialog = Primitive.Root;
const DialogTrigger = Primitive.Trigger;
const DialogTitle = Primitive.Title;
const DialogDescription = Primitive.Description;

function DialogContent({ className, children, ...props }: ComponentProps<typeof Primitive.Content>) {
  return <Primitive.Portal>
    <Primitive.Overlay data-slot="dialog-overlay" className="close-dialog-overlay" />
    <Primitive.Content data-slot="dialog-content" className={cn('close-dialog', className)} {...props}>
      {children}
    </Primitive.Content>
  </Primitive.Portal>;
}
export { Dialog, DialogTrigger, DialogContent, DialogTitle, DialogDescription };
