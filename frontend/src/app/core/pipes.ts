import { Pipe, PipeTransform } from '@angular/core';
import { groupLabel } from './models';

/** O_NEG -> O- */
@Pipe({ name: 'group', standalone: true })
export class GroupPipe implements PipeTransform {
  transform(v: string | null | undefined): string {
    return groupLabel(v);
  }
}

/** RED_CELLS -> Red cells */
@Pipe({ name: 'pretty', standalone: true })
export class PrettyPipe implements PipeTransform {
  transform(v: string | null | undefined): string {
    if (!v) return '';
    const s = v.replace(/_/g, ' ').toLowerCase();
    return s.charAt(0).toUpperCase() + s.slice(1);
  }
}

/** "in 3d 4h" / "2h ago" relative to now; good enough for shelf-life at a glance. */
@Pipe({ name: 'fromNow', standalone: true })
export class FromNowPipe implements PipeTransform {
  transform(v: string | null | undefined): string {
    if (!v) return '';
    const diff = new Date(v).getTime() - Date.now();
    const abs = Math.abs(diff);
    const d = Math.floor(abs / 86_400_000);
    const h = Math.floor((abs % 86_400_000) / 3_600_000);
    const m = Math.floor((abs % 3_600_000) / 60_000);
    const text = d > 0 ? `${d}d ${h}h` : h > 0 ? `${h}h ${m}m` : `${m}m`;
    return diff >= 0 ? `in ${text}` : `${text} ago`;
  }
}
