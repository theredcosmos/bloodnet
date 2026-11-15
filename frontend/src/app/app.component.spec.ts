import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app.component';
import { FromNowPipe, GroupPipe, PrettyPipe } from './core/pipes';

describe('AppComponent', () => {
  it('creates the root component', async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideRouter([])],
    }).compileComponents();
    expect(TestBed.createComponent(AppComponent).componentInstance).toBeTruthy();
  });
});

describe('pipes', () => {
  it('formats blood groups', () => {
    const p = new GroupPipe();
    expect(p.transform('O_NEG')).toBe('O-');
    expect(p.transform('AB_POS')).toBe('AB+');
  });

  it('prettifies enum names', () => {
    expect(new PrettyPipe().transform('RED_CELLS')).toBe('Red cells');
  });

  it('shows relative time', () => {
    const inTwoDays = new Date(Date.now() + 2 * 86_400_000 + 60_000).toISOString();
    expect(new FromNowPipe().transform(inTwoDays)).toContain('in 2d');
  });
});
