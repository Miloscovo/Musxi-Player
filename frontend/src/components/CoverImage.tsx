import { useCoverImage } from '../composables/useCoverImage';
export default function CoverImage({ url, alt = '' }: { url: string; alt?: string }) {
  const image = useCoverImage(url);
  return image ? <img className="cover" src={image} alt={alt} /> : <span className="cover placeholder" aria-hidden="true">♫</span>;
}
