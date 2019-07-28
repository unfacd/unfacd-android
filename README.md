# unfacd Android 

This is the android client for the unfacd network. unfacd is a fusion messaging/social network. Download [unfacd](https://play.google.com/store/apps/details?id=com.unfacd.android) to try it out. You'll need a valid email to register, as you'll be sent a verification link. Once you are verified, there won't be any future requirement to "sign on" again.

## Relationship with Signal App

This software started off as a fork from the Signal messaging app, since it contained useful code abstractions for the android development environment, however unfacd is unaffiliated with
Signal at any level (technical, philosophical, etc...); and code reuse is purely based on technical considerations and to facilitate rapid prototyping.
Furthermore, unfacd runs its own backend, using its own open source software and protocols. 
It is therefore not useful to try and draw parallels based on  philosophical benchmarks.

unfacd doesn't contain advertisements or trackers and you are welcome to verify the publicly available app for example using [this free service](https://reports.exodus-privacy.eu.org/en/analysis/submit/).

## On encryption
Whilst we kept Signal's original end-to-end encryption, we don't necessarily aim, or claim to be compliant with it. 
As such, it is currently only available for private conversations between two contacts. Phone/video calls are also end-to-end thanks to WebRTC. 
All other exchanges are based on open standards, point-to-point encryption on the back of TLS.  

# Legal things
## Cryptography Notice

This distribution includes cryptographic software. The country in which you currently reside may have restrictions on the import, possession, use, and/or re-export to another country, of encryption software.
BEFORE using any encryption software, please check your country's laws, regulations and policies concerning the import, possession, or use, and re-export of encryption software, to see if this is permitted.
See <http://www.wassenaar.org/> for more information.

The U.S. Government Department of Commerce, Bureau of Industry and Security (BIS), has classified this software as Export Commodity Control Number (ECCN) 5D002.C.1, which includes information security software using or performing cryptographic functions with asymmetric algorithms.
The form and manner of this distribution makes it eligible for export under the License Exception ENC Technology Software Unrestricted (TSU) exception (see the BIS Export Administration Regulations, Section 740.13) for both object code and source code.

## License and copyright notices

Copyright (C) 2015-2019 unfacd works
 
Copyright 2011 Whisper Systems

Copyright 2013-2016 Open Whisper Systems

Licensed under the GPLv3: http://www.gnu.org/licenses/gpl-3.0.html
