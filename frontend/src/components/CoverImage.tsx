import { useCoverImage } from '../composables/useCoverImage';
export default function CoverImage({ url, alt = '', animateLoading = false }: { url: string; alt?: string; animateLoading?: boolean }) {
  const { image, loading } = useCoverImage(url);
  const animated = animateLoading && loading;
  return image ? <img className="cover" src={image} alt={alt} /> : <span className={`cover placeholder${animated ? ' cover-loading' : ''}`} aria-hidden="true">{animated ? null : '♫'}</span>;
}
