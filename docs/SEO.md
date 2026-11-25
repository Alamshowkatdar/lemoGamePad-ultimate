# Website SEO notes

The public site is <https://alamshowkatdar.github.io/lemoGamePad-ultimate/> (GitHub Pages, served from `docs/`).

## Target searches

- Brand: `lemogamepad`, `lemogamepad ultimate`
- High-intent long tail: `use android phone as gamepad for pc`, `android phone as xbox controller`, `phone as game controller windows`, `wireless gamepad for pc without dongle`, `browser gamepad for pc`, `8 player gamepad pc`

## Changes already in the site

- Keyword-focused `<title>`, `<meta name="description">`, and `<link rel="canonical">`.
- Single keyword-rich `<h1>`: “Turn your Android phone into a gamepad for your PC”; existing punchy line moved to the eyebrow.
- `index, follow` robots policy, Open Graph and Twitter card tags (including image dimensions and alt text).
- JSON-LD structured data: `SoftwareApplication` (version, OS, free offer, features) and `FAQPage` matching the visible FAQ answers.
- FAQ section heading and question wording now match the structured data.
- `robots.txt` and `sitemap.xml` served from the site root; the sitemap lists the homepage and all public guides.
- A Jekyll layout (`_layouts/default.html`) gives every published guide its own keyword-focused title, description, canonical URL, Open Graph tags, navigation and `TechArticle` structured data. `_config.yml` supplies page metadata without adding front matter to the Markdown files.
- The homepage links to the on-site guides instead of GitHub blob pages, plus a documentation hub section with keyword-rich anchor text.
- Internal-only pages (review notes, release checklist, SEO notes) are `noindex`.
- The hero now uses a real screenshot of the Android app (`assets/app-pad-blue.png`, captured in landscape from the connected phone at v2.1.5 with the default Midnight Blue theme and PlayStation face labels) instead of a CSS mock-up, with descriptive alt text, a preload hint, and the same image as the social preview.
- GitHub repository homepage points to the website, and relevant topics were added.

## Required off-site steps (cannot be done in code)

1. Push the changes to `main` so GitHub Pages republishes the site.
2. Verify the domain in [Google Search Console](https://search.google.com/search-console) (URL-prefix property works for GitHub Pages) and submit `https://alamshowkatdar.github.io/lemoGamePad-ultimate/sitemap.xml`. Use “Request indexing” on the homepage.
3. Add the same sitemap in [Bing Webmaster Tools](https://www.bing.com/webmasters/) (it can import from Search Console).
4. Earn links to the website itself: relevant Reddit/Android/gaming/emulator communities, `alternativeTo`, Awesome lists, blog posts, and the READMEs of other owned projects. Link text should mention the product name and what it does.
5. Keep the release version in `index.html`, the README, and GitHub releases in sync — stale pages lose trust and clicks.

Ranking #1 is not guaranteed by on-page changes alone. The biggest factors for competitive terms such as “phone as gamepad for PC” are indexed pages, relevant links, and user engagement after the click.
