import { useEffect, useRef, useState } from 'react';
import { Alert, AlertDescription } from './ui/alert';

export type Message = { text: string; error?: boolean; token?: string };
type VisibleMessage = Message & { id: number };

function TimedAlert({ message, onExpire }: { message: VisibleMessage; onExpire: (id: number) => void }) {
  useEffect(() => {
    const timer = window.setTimeout(() => onExpire(message.id), 3000);
    return () => window.clearTimeout(timer);
  }, [message.id, onExpire]);
  return <Alert variant={message.error ? 'destructive' : 'default'} role={message.error ? 'alert' : 'status'}>
    <AlertDescription>{message.text}</AlertDescription>
  </Alert>;
}

export default function MessageAlerts({ messages }: { messages: Record<string, Message> }) {
  const previous = useRef<Record<string, Message>>({});
  const nextId = useRef(0);
  const [visible, setVisible] = useState<VisibleMessage[]>([]);
  const expire = useRef((id: number) => setVisible(items => items.filter(item => item.id !== id))).current;
  useEffect(() => {
    const changed = Object.entries(messages).filter(([source, message]) => message.text &&
      (message.text !== previous.current[source]?.text || message.token !== previous.current[source]?.token));
    previous.current = messages;
    if (!changed.length) return;
    const added = changed.map(([, message]) => ({ ...message, id: ++nextId.current }));
    setVisible(items => [...items, ...added].filter((item, index, all) =>
      !all.slice(index + 1).some(other => other.text === item.text)).slice(-3));
  }, [messages]);
  return <div className="message-alerts" aria-label="消息提示">
    {visible.map(message => <TimedAlert key={message.id} message={message} onExpire={expire} />)}
  </div>;
}
