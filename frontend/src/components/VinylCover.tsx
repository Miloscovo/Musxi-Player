import { useState, type CSSProperties, type SyntheticEvent } from 'react';
import CoverImage from './CoverImage';
import { sampleCoverColor } from '../composables/vinylColor';

export default function VinylCover({ url, playing, onColor }: { url: string; playing: boolean; onColor?: (url: string, color: string) => void }) {
  const [sample, setSample] = useState({ url: '', color: '' });
  function sampleCover(event: SyntheticEvent<HTMLDivElement>) {
    const image = event.target;
    if (!(image instanceof HTMLImageElement)) return;
    const color = sampleCoverColor(image);
    setSample({ url, color }); onColor?.(url, color);
  }
  const color = url && sample.url === url ? sample.color : '';
  return <div className="vinyl-cover" data-playing={playing} onLoadCapture={sampleCover}
    style={color ? { '--vinyl-color': color } as CSSProperties : undefined}>
    <div className="vinyl-disc"><CoverImage url={url} animateLoading /></div>
  </div>;
}
