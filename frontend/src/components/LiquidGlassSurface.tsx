import { useId } from 'react';
import { liquidGlassMap } from '../composables/liquidGlassMap';

// Background-layer refraction adapted from liquid-glass-react (MIT); text is never filtered.
export default function LiquidGlassSurface({ refract = true }: { refract?: boolean }) {
  const id = `liquid-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`;
  return <span className="liquid-glass-surface" aria-hidden="true">
    {refract && <>
      <svg width="0" height="0" className="liquid-glass-defs">
        <defs><filter id={id} x="-15%" y="-15%" width="130%" height="130%" colorInterpolationFilters="sRGB">
          <feImage href={liquidGlassMap} x="0" y="0" width="100%" height="100%" preserveAspectRatio="none" result="map"/>
          <feDisplacementMap in="SourceGraphic" in2="map" scale="-12" xChannelSelector="R" yChannelSelector="B"/>
        </filter></defs>
      </svg>
      <span className="liquid-glass-blur"/>
      <span className="liquid-glass-warp" style={{ filter: `url(#${id})` }}/>
    </>}
    <span className="liquid-glass-tint"/>
    <span className="liquid-glass-shine"/>
  </span>;
}
