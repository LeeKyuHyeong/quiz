/**
 * 테이블 행 확장/축소 공통 함수
 * admin/room/list.html, admin/history/list.html,admin/chat/list.html 등에서 사용
 */

function toggleRowExpand(row) {
    if (window.innerWidth <= 768) {
        row.classList.toggle('expanded');
    }
}
