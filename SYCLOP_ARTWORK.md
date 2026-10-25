# Syclop artwork provenance

Syclop's application/integration code follows this repository's GNU GPLv3 license. The mascot PNGs and animation assets below were supplied by the Syclop project owner in **sysclop logo asset dark mode** for this integration. They are separate from the upstream Nuvio artwork. The supplied bundle does not contain an explicit artwork license or artist attribution; this notice records the supplied provenance without inventing either or silently relicensing the images.

The selected files are the bundle's **01 Primary Head**, **02 App Icon** and the **02 Animations** loading/error assets, copied byte for byte. No crop, recolour, pixel enlargement, background removal, generated replacement or newly authored animation is part of this integration. Android XML applies layout bounds, icon insets and the normal adaptive mask. The app retains its black surfaces, light Inter wordmark and existing layout/navigation.

| Resource | Supplied source path | SHA-256 |
|---|---|---|
| `drawable-nodpi/syclop_primary_head_512.png` | `sysclop logo asset dark mode/01_Logos/01_Primary_Head/logo_512.png` | `f201e76d00f04b3a7b47c86e4f42afe2ace4cae5defccc268845efd10d4df8d7` |
| `drawable-nodpi/syclop_app_icon_512.png` | `sysclop logo asset dark mode/01_Logos/02_App_Icon/logo_512.png` | `17fed64b0797b841a600bd2f03a573361d42f61d0959d284e65f3f98c646bb49` |
| `mipmap-nodpi/syclop_launcher.png` | `sysclop logo asset dark mode/01_Logos/02_App_Icon/logo_256.png` | `ac0848230eb0a5732579c3bd5bc779fc7c28dce408a911c3b97646eec8d91fea` |

The pack README identifies these as dark-adapted exports with retained alpha transparency. The supplied app icon contains its own dark navy tile and transparent outer corners, retained unchanged. It is not represented as a platform-certified submission icon. The optional 09 rim-lit head is not used. The original light bundle and earlier staging remain outside production.

The loading and error WebPs are the supplied transparent dark-mode animations: 24 frames over 1.68 seconds and 18 frames over 1.62 seconds respectively. Their matching PNG previews are supplied stills for reduced motion and devices without native animated-WebP support. All four files retain their original pixels and alpha transparency; the fixed-background GIF variants are not included. The integration displays the illustrations alongside live status/error text and existing retry actions.

| Resource | Supplied source path | SHA-256 |
|---|---|---|
| `raw/syclop_loading_dark.webp` | `sysclop logo asset dark mode/02_Animations/01_Search_Loading/search_loading.webp` | `1d837da591940175a9e3cfe6c51a20a326ac1b8c21bee56fc91e539e03312968` |
| `raw/syclop_error_dark.webp` | `sysclop logo asset dark mode/02_Animations/02_Error_Alert/error_alert.webp` | `79852e4041fa0300be67a30f2376a58485f06c04a6e95eb105f49531553c2702` |
| `drawable-nodpi/syclop_loading_dark_still.png` | `sysclop logo asset dark mode/02_Animations/01_Search_Loading/preview.png` | `9b6c4ff4c3c877be5bdfe50396eff91a8336100f8a798290cd70e6bedfada637` |
| `drawable-nodpi/syclop_error_dark_still.png` | `sysclop logo asset dark mode/02_Animations/02_Error_Alert/preview.png` | `75ffbe92b60646ae9260ecec9a61bd935d04a7acdb5525e418af0454f8772a91` |

The Android TV banner is a native 320×180 dp black composition of the unchanged primary head and the existing original white Syclop geometric letter paths. Website banners, hero/mobile illustrations, older concept art, GIF variants and animation sheets are not shipped in the TV app.

The existing Inter typeface and its upstream open-font license are unchanged. Original repository licenses/notices remain in place. This file is an attribution/provenance notice, not a claim of endorsement by the upstream Nuvio project or by Apple.
