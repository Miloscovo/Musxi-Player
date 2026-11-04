import { useCoverImage } from '../composables/useCoverImage';
import type { CoverSize } from '../native/library';
export default function CoverImage({ url, alt = '', animateLoading = false, size = 1200, fallbackImage = '' }: { url: string; alt?: string; animateLoading?: boolean; size?: CoverSize; fallbackImage?: string }) {
  const { image, loading } = useCoverImage(url, size);
  const animated = animateLoading && loading;
  return image || fallbackImage ? <img className="cover" src={image || fallbackImage} alt={alt} /> : <span className={`cover placeholder${animated ? ' cover-loading' : ''}`} aria-hidden="true">{animated ? null : '♫'}</span>;
}
