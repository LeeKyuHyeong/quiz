/**
 * YouTube IFrame API Player Manager
 * 공통 모듈: 모든 게임 모드에서 YouTube 영상 재생에 사용
 */
const YouTubePlayerManager = {
    player: null,
    isReady: false,
    containerId: null,
    onReadyCallback: null,
    onStateChangeCallback: null,
    onErrorCallback: null,
    lastError: null,
    currentVideoId: null,
    retryCount: 0,
    MAX_ERROR_RETRIES: 1,

    // YouTube 에러 코드 설명
    ERROR_CODES: {
        2: '잘못된 파라미터',
        5: 'HTML5 플레이어 오류',
        100: '영상을 찾을 수 없음',
        101: '임베드가 차단됨',
        150: '임베드가 차단됨'
    },

    /**
     * YouTube IFrame API 초기화
     * @param {string} containerId - YouTube 플레이어를 삽입할 DOM 요소 ID
     * @param {object} options - 추가 옵션 (onReady, onStateChange, onError 콜백, width·height — 없으면 0 = 숨김)
     * @returns {Promise} - 플레이어 준비 완료 시 resolve
     */
    init(containerId, options = {}) {
        this.containerId = containerId;
        this.width = options.width || '0';
        this.height = options.height || '0';
        this.onReadyCallback = options.onReady || null;
        this.onStateChangeCallback = options.onStateChange || null;
        this.onErrorCallback = options.onError || null;

        return new Promise((resolve) => {
            if (window.YT && window.YT.Player) {
                this.createPlayer(resolve);
                return;
            }

            if (!document.querySelector('script[src*="youtube.com/iframe_api"]')) {
                const tag = document.createElement('script');
                tag.src = 'https://www.youtube.com/iframe_api';
                document.body.appendChild(tag);
            }

            window.onYouTubeIframeAPIReady = () => {
                this.createPlayer(resolve);
            };
        });
    },

    /**
     * YouTube 플레이어 생성
     */
    createPlayer(resolve) {
        this.player = new YT.Player(this.containerId, {
            height: this.height,
            width: this.width,
            playerVars: {
                autoplay: 0,
                controls: 0,
                disablekb: 1,
                enablejsapi: 1,
                modestbranding: 1,
                rel: 0,
                showinfo: 0,
                playsinline: 1,
                origin: window.location.origin
            },
            events: {
                onReady: (e) => {
                    this.isReady = true;
                    Debug.log('YouTube Player ready');
                    if (this.onReadyCallback) this.onReadyCallback(e);
                    resolve();
                },
                onStateChange: (e) => {
                    if (this.onStateChangeCallback) this.onStateChangeCallback(e);
                },
                onError: (e) => {
                    const errorCode = e.data;
                    const errorMessage = this.ERROR_CODES[errorCode] || '알 수 없는 오류';
                    // console.error('YouTube Player Error:', errorCode, errorMessage);

                    // Error 2일 때 startTime=0으로 한 번 재시도 (startTime 문제일 수 있음)
                    if (errorCode === 2 && this.retryCount < this.MAX_ERROR_RETRIES && this.currentVideoId) {
                        this.retryCount++;
                        Debug.log('Error 2 재시도 중... (startTime=0, 시도:', this.retryCount + '/' + this.MAX_ERROR_RETRIES + ')');
                        this.player.cueVideoById({
                            videoId: this.currentVideoId,
                            startSeconds: 0
                        });
                        return; // 재시도 후 대기
                    }

                    // 재시도 카운터 리셋
                    this.retryCount = 0;

                    this.lastError = {
                        code: errorCode,
                        message: errorMessage,
                        timestamp: Date.now(),
                        // Error 2: 잘못된 파라미터 (영상 삭제/비공개 또는 startTime 초과 시 발생)
                        isPlaybackError: [2, 100, 101, 150].includes(errorCode)
                    };

                    if (this.onErrorCallback) this.onErrorCallback(e, this.lastError);
                }
            }
        });
    },

    /**
     * 영상 로드 (재생 대기 상태)
     * @returns {boolean} 로드 시도 성공 여부
     */
    loadVideo(videoId, startTime = 0) {
        if (!this.isReady || !this.player) {
            console.warn('YouTube Player not ready');
            return false;
        }
        // 이전 에러 상태 초기화
        this.lastError = null;
        this.currentVideoId = videoId;
        this.retryCount = 0;

        // startTime이 음수면 0으로 보정
        var safeStartTime = Math.max(0, startTime || 0);

        this.player.cueVideoById({
            videoId: videoId,
            startSeconds: safeStartTime
        });
        return true;
    },

    /**
     * 영상 로드 및 자동 재생
     * @returns {boolean} 로드 시도 성공 여부
     */
    loadAndPlay(videoId, startTime = 0) {
        if (!this.isReady || !this.player) {
            console.warn('YouTube Player not ready');
            return false;
        }
        this.lastError = null;
        this.currentVideoId = videoId;
        this.retryCount = 0;

        var safeStartTime = Math.max(0, startTime || 0);

        // loadVideoById는 로드 후 자동 재생
        this.player.loadVideoById({
            videoId: videoId,
            startSeconds: safeStartTime
        });
        return true;
    },

    /**
     * 재생
     */
    play() {
        if (this.isReady && this.player) {
            this.player.playVideo();
        }
    },

    /**
     * 일시정지
     */
    pause() {
        if (this.isReady && this.player) {
            this.player.pauseVideo();
        }
    },

    /**
     * 정지
     */
    stop() {
        if (this.isReady && this.player) {
            this.player.stopVideo();
        }
    },

    /**
     * 특정 시간으로 이동
     */
    seekTo(seconds, allowSeekAhead = true) {
        if (this.isReady && this.player) {
            this.player.seekTo(seconds, allowSeekAhead);
        }
    },

    /**
     * 현재 재생 시간 조회
     */
    getCurrentTime() {
        if (this.isReady && this.player) {
            return this.player.getCurrentTime();
        }
        return 0;
    },

    /**
     * 영상 전체 길이 조회
     */
    getDuration() {
        if (this.isReady && this.player) {
            return this.player.getDuration();
        }
        return 0;
    },

    /**
     * 재생 상태 확인
     */
    isPlaying() {
        if (this.isReady && this.player) {
            return this.player.getPlayerState() === YT.PlayerState.PLAYING;
        }
        return false;
    },

    /**
     * 현재 플레이어 상태 조회
     */
    getPlayerState() {
        if (this.isReady && this.player) {
            return this.player.getPlayerState();
        }
        return -1;
    },

    /**
     * 볼륨 설정
     */
    setVolume(volume) {
        if (this.isReady && this.player) {
            this.player.setVolume(volume);
        }
    },

    /**
     * 음소거
     */
    mute() {
        if (this.isReady && this.player) {
            this.player.mute();
        }
    },

    /**
     * 음소거 해제
     */
    unMute() {
        if (this.isReady && this.player) {
            this.player.unMute();
        }
    },

    /**
     * 플레이어 준비 상태 확인
     */
    ready() {
        return this.isReady;
    },

    /**
     * 마지막 에러 정보 조회
     */
    getLastError() {
        return this.lastError;
    },

    /**
     * 에러 상태 초기화
     */
    clearError() {
        this.lastError = null;
    },

    /**
     * 재생 불가 에러인지 확인
     */
    hasPlaybackError() {
        return this.lastError && this.lastError.isPlaybackError;
    },

    /**
     * 에러 메시지 조회
     */
    getErrorMessage(errorCode) {
        return this.ERROR_CODES[errorCode] || '알 수 없는 오류';
    }
};
