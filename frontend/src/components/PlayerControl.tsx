import type { ComponentProps } from 'react';
import { motion, useReducedMotion } from 'motion/react';
import { Button } from './ui/button';

export default function PlayerControl({ children, ...props }: ComponentProps<typeof Button>) {
  const reduced = useReducedMotion();
  const animate = !reduced && !props.disabled;
  return <Button {...props} asChild><motion.button initial={false}
    whileHover={animate ? { scale: 1.08 } : undefined}
    whileTap={animate ? { scale: 0.92 } : undefined}
    transition={{ type: 'spring', stiffness: 450, damping: 30 }}>{children}</motion.button></Button>;
}
