// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.vici.ashreader.engine.mupdf

/**
 * MuPDF 사용자 CSS(`Context.setUserCSS`)는 전역이다. HTML·EPUB 문서를 열 때 CSS를 바꾸고 여는 동안
 * 다른 스레드가 CSS를 바꾸지 못하게 이 잠금을 잡는다([MupdfDoc.open], [PagedComposer]).
 */
object MupdfLock
