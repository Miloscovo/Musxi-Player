// Adapted from shadcn/ui d75a96ab781f3d659be1ad287347d5887ce9f2fc.
// Copyright (c) 2023 shadcn. MIT: licenses/frontend/shadcn-ui-LICENSE.txt.
// Individual Radix imports and existing theme CSS.
import type { ComponentProps } from 'react';
import * as Primitive from '@radix-ui/react-tooltip';
import { cn } from '@/lib/utils';
const Tooltip = Primitive.Root;
const TooltipTrigger = Primitive.Trigger;
const TooltipProvider = Primitive.Provider;
function TooltipContent({ className, sideOffset = 8, ...props }: ComponentProps<typeof Primitive.Content>) {
  return <Primitive.Portal><Primitive.Content data-slot="tooltip-content" className={cn('music-tooltip', className)}
    sideOffset={sideOffset} collisionPadding={8} {...props}/></Primitive.Portal>;
}
export { Tooltip, TooltipTrigger, TooltipProvider, TooltipContent };
