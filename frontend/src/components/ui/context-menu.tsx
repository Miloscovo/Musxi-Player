// Adapted from shadcn/ui d75a96ab781f3d659be1ad287347d5887ce9f2fc.
// Copyright (c) 2023 shadcn. MIT: licenses/frontend/shadcn-ui-LICENSE.txt.
// Only used primitives; individual Radix imports, local cn and existing theme CSS.
import type { ComponentProps } from 'react';
import * as Primitive from '@radix-ui/react-context-menu';
import { cn } from '@/lib/utils';

const ContextMenu = Primitive.Root;
const ContextMenuTrigger = Primitive.Trigger;
const ContextMenuSub = Primitive.Sub;

function ContextMenuContent({ className, ...props }: ComponentProps<typeof Primitive.Content>) {
  return <Primitive.Portal><Primitive.Content data-slot="context-menu-content"
    className={cn('song-context-menu', className)} collisionPadding={8} {...props}/></Primitive.Portal>;
}
function ContextMenuItem({ className, ...props }: ComponentProps<typeof Primitive.Item>) {
  return <Primitive.Item data-slot="context-menu-item" className={cn('song-context-item', className)} {...props}/>;
}
function ContextMenuSubTrigger({ className, children, ...props }: ComponentProps<typeof Primitive.SubTrigger>) {
  return <Primitive.SubTrigger data-slot="context-menu-sub-trigger" className={cn('song-context-item', className)} {...props}>
    {children}<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 5 7 7-7 7"/></svg>
  </Primitive.SubTrigger>;
}
function ContextMenuSubContent({ className, ...props }: ComponentProps<typeof Primitive.SubContent>) {
  return <Primitive.Portal><Primitive.SubContent data-slot="context-menu-sub-content"
    className={cn('song-context-menu', className)} collisionPadding={8} {...props}/></Primitive.Portal>;
}

export { ContextMenu, ContextMenuTrigger, ContextMenuContent, ContextMenuItem, ContextMenuSub, ContextMenuSubTrigger, ContextMenuSubContent };
