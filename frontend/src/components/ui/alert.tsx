// Adapted from shadcn/ui d75a96ab781f3d659be1ad287347d5887ce9f2fc.
// Copyright (c) 2023 shadcn. MIT: licenses/frontend/shadcn-ui-LICENSE.txt.
// Changes: local cn and existing theme CSS instead of utility classes.
import type { ComponentProps } from 'react';
import { cn } from '@/lib/utils';

function Alert({ className, variant = 'default', ...props }:
  ComponentProps<'div'> & { variant?: 'default' | 'destructive' }) {
  return <div data-slot="alert" data-variant={variant} role="alert"
    className={cn('message-alert', className)} {...props} />;
}
function AlertDescription({ className, ...props }: ComponentProps<'div'>) {
  return <div data-slot="alert-description" className={cn('message-alert-description', className)} {...props} />;
}
export { Alert, AlertDescription };
