// Adapted from shadcn/ui d75a96ab781f3d659be1ad287347d5887ce9f2fc.
// Copyright (c) 2023 shadcn. MIT: licenses/frontend/shadcn-ui-LICENSE.txt.
// Changes: individual Radix import, local cn and existing theme CSS.
import type { ComponentProps } from 'react';
import * as Primitive from '@radix-ui/react-popover';
import { cn } from '@/lib/utils';

const Popover = Primitive.Root;
const PopoverTrigger = Primitive.Trigger;
function PopoverContent({ className, ...props }: ComponentProps<typeof Primitive.Content>) {
  return <Primitive.Portal><Primitive.Content data-slot="popover-content"
    className={cn('queue-panel', className)} collisionPadding={12} {...props} /></Primitive.Portal>;
}
export { Popover, PopoverTrigger, PopoverContent };
